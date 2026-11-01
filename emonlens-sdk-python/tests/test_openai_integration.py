"""emonlens.integrations.openai 集成测试（本地 mock server + duck-typing 假 OpenAI 响应）。

覆盖真实用户路径（from emonlens.integrations.openai import OpenAI / wrap_openai）：
1. 独立调用 → 自建 Trace，span 带 model/tokens/输出/耗时口径；
2. 缓存命中三种字段形态：usage.prompt_tokens_details.cached_tokens、
   prompt_cache_hit_tokens（DeepSeek 兼容）、cached_tokens 直挂；
3. 嵌套在 @observe Trace 内 → 作为子 span（parentSpanId 指向当前 span）；
4. 调用抛错 → span 标 ERROR + errorType，且异常继续抛给调用方（不吞）。
"""
import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from test_sdk import MockServer                                  # noqa: E402

from emonlens import Trace, flush, init                          # noqa: E402
from emonlens.client import set_client                           # noqa: E402
from emonlens.integrations import openai as oai                  # noqa: E402


class _Usage:
    def __init__(self, prompt=0, completion=0, details=None, hit=None, direct=None):
        self.prompt_tokens = prompt
        self.completion_tokens = completion
        if details is not None:
            self.prompt_tokens_details = details
        if hit is not None:
            self.prompt_cache_hit_tokens = hit
        if direct is not None:
            self.cached_tokens = direct


class _Details:
    def __init__(self, cached):
        self.cached_tokens = cached


class _Msg:
    def __init__(self, content):
        self.content = content


class _Choice:
    def __init__(self, content):
        self.message = _Msg(content)


class _Response:
    def __init__(self, content, usage, model="gpt-4o-mini"):
        self.choices = [_Choice(content)]
        self.usage = usage
        self.model = model


class _Completions:
    """只实现集成用到的属性读取（getattr 鸭子类型），不依赖 openai 包。"""

    def __init__(self, response=None, error=None):
        self._response = response
        self._error = error
        self.calls = []

    def create(self, *args, **kwargs):
        self.calls.append(kwargs)
        if self._error is not None:
            raise self._error
        return self._response


class _Chat:
    def __init__(self, completions):
        self.completions = completions


class _FakeClient:
    def __init__(self, completions):
        self.chat = _Chat(completions)


class OpenAIIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        init(host=f"http://127.0.0.1:{self.server.server_address[1]}",
             ingest_key="k", agent="test-agent", flush_interval=0.2)

    def tearDown(self):
        from emonlens.client import set_client
        flush(timeout=2)
        set_client(None)
        self.server.shutdown()

    def _spans(self):
        out = []
        for _, ss in self.server.requests:
            out.extend(ss)
        return out

    def test_standaloneCallRecordsLlmSpan(self):
        resp = _Response("你好", _Usage(prompt=120, completion=30))
        client = oai.wrap_openai(_FakeClient(_Completions(response=resp)))
        got = client.chat.completions.create(model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])
        self.assertEqual(got, resp)                     # 返回值必须是原始响应
        flush(timeout=2)
        spans = self._spans()
        span = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(span["modelName"], "gpt-4o-mini")
        self.assertEqual(span["output"], "你好")
        self.assertEqual(span["inputTokens"], 120)
        self.assertEqual(span["outputTokens"], 30)
        self.assertNotIn("cachedInputTokens", span)      # 无缓存信息时不写 0，避免污染统计
        self.assertEqual(span["status"], "OK")

    def test_cachedTokensFromPromptDetails(self):
        resp = _Response("答", _Usage(prompt=1000, completion=50, details=_Details(800)))
        client = oai.wrap_openai(_FakeClient(_Completions(response=resp)))
        client.chat.completions.create(model="m", messages=[])
        flush(timeout=2)
        span = next(s for s in self._spans() if s["spanType"] == "llm")
        self.assertEqual(span["inputTokens"], 1000)
        self.assertEqual(span["cachedInputTokens"], 800)   # 一等字段

    def test_cachedTokensFromProviderCompatibleFields(self):
        # DeepSeek 等兼容端点：usage 直挂 prompt_cache_hit_tokens
        resp = _Response("答", _Usage(prompt=500, completion=20, hit=321))
        client = oai.wrap_openai(_FakeClient(_Completions(response=resp)))
        client.chat.completions.create(model="m", messages=[])
        # 另一种：直挂 cached_tokens
        resp2 = _Response("答2", _Usage(prompt=500, completion=20, direct=222))
        oai.wrap_openai(_FakeClient(_Completions(response=resp2))).chat.completions.create(model="m", messages=[])
        flush(timeout=2)
        spans = [s for s in self._spans() if s["spanType"] == "llm"]
        cached = sorted(s.get("cachedInputTokens", 0) for s in spans)
        self.assertEqual(cached, [222, 321])

    def test_nestedCallIsChildSpanOfObservedTrace(self):
        resp = _Response("ok", _Usage(prompt=10, completion=5))
        client = oai.wrap_openai(_FakeClient(_Completions(response=resp)))
        with Trace(name="agent-run") as t:
            client.chat.completions.create(model="m", messages=[])
        flush(timeout=2)
        spans = self._spans()
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["traceId"], t.trace_id)          # 与 Trace 同一 trace
        self.assertEqual(llm["parentSpanId"], "root")          # 挂在 Trace 的根 span 下

    def test_errorIsRecordedAndReraised(self):
        client = oai.wrap_openai(_FakeClient(_Completions(error=RuntimeError("boom"))))
        with self.assertRaises(RuntimeError):
            client.chat.completions.create(model="m", messages=[])
        flush(timeout=2)
        span = next(s for s in self._spans() if s["spanType"] == "llm")
        self.assertEqual(span["status"], "ERROR")
        self.assertEqual(span["errorType"], "RuntimeError")

    def test_spanDurationReflectsCallWallClock(self):
        # 手工走查发现：_record 在响应返回后才执行，早先用 now_iso() 取开始时刻，
        # 导致 startTime == endTime，服务端重算出的 durationMs 恒为 0（所有 OpenAI llm span 都没耗时）。
        # 必须用调用前捕获的 start 还原真实开始时刻。
        resp = _Response("ok", _Usage(prompt=10, completion=5))

        class _SlowCompletions(_Completions):
            def create(self, *args, **kwargs):
                self.calls.append(kwargs)
                time.sleep(0.3)
                return resp

        client = oai.wrap_openai(_FakeClient(_SlowCompletions()))
        client.chat.completions.create(model="m", messages=[])
        flush(timeout=2)
        span = next(s for s in self._spans() if s["spanType"] == "llm")
        self.assertLessEqual(span["startTime"], span["endTime"])   # 毫秒 ISO 可字典序比较
        delta = _ms(span["endTime"]) - _ms(span["startTime"])
        self.assertGreaterEqual(delta, 250, "span 耗时应覆盖真实调用 wall clock")


def _ms(iso: str) -> float:
    """把 'YYYY-MM-DDTHH:MM:SS.mmm' 解析成 epoch 毫秒（本地时区）。"""
    import calendar
    import time as _t
    head, _, frac = iso.partition(".")
    base = calendar.timegm(_t.strptime(head, "%Y-%m-%dT%H:%M:%S")) - _t.timezone
    return base * 1000 + int(frac.ljust(3, "0")[:3])


if __name__ == "__main__":
    unittest.main()
