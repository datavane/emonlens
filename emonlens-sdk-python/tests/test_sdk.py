"""EmonLens SDK P0 集成测试（本地 mock server，stdlib unittest，pytest 兼容）。

验收项（设计方案 §10）：
1. Trace 3 span 批量到达、字段正确（agentKey/parent/status）
2. 异常 → 根 span ERROR + errorType，且异常不吞
3. 未配置 → no-op 零上报
4. 5xx 重试后成功；持续失败丢弃不崩
5. flush 同步排空
"""
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from emonlens import Trace, flush, init
from emonlens.client import get_client, set_client


class MockServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.requests = []          # [(path, spans)]
        self.fail_times = 0         # 前 N 次返回 500
        self.lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"[]")
        with self.server.lock:
            self.server.requests.append((self.path, body))
            should_fail = self.server.fail_times > 0
            if should_fail:
                self.server.fail_times -= 1
        if should_fail:
            self.send_response(500)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"code":0,"data":{"accepted":%d}}' % len(body))

    def log_message(self, *a):
        pass


class SDKTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.host = f"http://127.0.0.1:{self.server.server_address[1]}"
        # 隔离环境变量
        self._env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("EMONLENS")}

    def tearDown(self):
        set_client(None)
        self.server.shutdown()
        for k, v in self._env.items():
            os.environ[k] = v

    def _init(self, **kw):
        init(host=self.host, ingest_key="tk-test", agent="test-agent", **kw)

    def test_env_only_bootstrap_reports(self):
        """只设环境变量、不调 init()：get_client() 惰性自动初始化并正常上报
        （README「三步接入」语义回归——此前该路径静默 no-op）。"""
        os.environ.update(EMONLENS_HOST=self.host, EMONLENS_INGEST_KEY="tk-env",
                          EMONLENS_AGENT="env-agent", EMONLENS_FLUSH_INTERVAL="0.05")
        set_client(None)                       # 清掉既有实例，强制走惰性路径
        c = get_client()
        self.assertIsNotNone(c)
        self.assertTrue(c.cfg.configured)
        with Trace(session_id="env-s1", input="问题") as tr:
            tr.tool("t1", input="i", output="o")
        flush()
        spans = [s for _, ss in self.server.requests for s in ss]
        self.assertEqual(len(spans), 2)        # root + tool
        self.assertTrue(all(s["agentKey"] == "env-agent" for s in spans))

    def test_env_incomplete_stays_noop(self):
        """环境变量不齐（缺 key）：惰性初始化得到 no-op 客户端，emit 静默、不上报。"""
        os.environ.update(EMONLENS_HOST=self.host, EMONLENS_INGEST_KEY="")
        set_client(None)
        c = get_client()
        self.assertFalse(c.cfg.configured)
        with Trace(input="x") as tr:
            tr.tool("t", input="i", output="o")
        flush()
        self.assertEqual(self.server.requests, [])

    def test_object_input_serialized_not_rejected(self):
        """tool/llm 的 input/output 传 dict 等对象：SDK 侧序列化为字符串，
        服务端不会因反序列化失败整批 400 拒收（此前整批丢失）。"""
        self._init(flush_interval=0.05)
        with Trace(session_id="obj-s1", input={"question": "工单进度"}) as tr:
            tr.tool("query_db", input={"id": 1}, output={"rows": [1, 2]})
            tr.llm("call", model="m", input={"p": 1}, output={"a": 2})
        flush()

        self.assertEqual(len(self.server.requests), 1)
        _, spans = self.server.requests[0]
        self.assertEqual(len(spans), 3)                     # 整批受理而非部分拒绝
        root = next(s for s in spans if s["spanId"] == "root")
        tool = next(s for s in spans if s["spanType"] == "tool")
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(root["input"], '{"question": "工单进度"}')
        self.assertEqual(tool["input"], '{"id": 1}')
        self.assertEqual(tool["output"], '{"rows": [1, 2]}')
        self.assertEqual(llm["input"], '{"p": 1}')

    def test_llm_cached_tokens_field(self):
        """tr.llm(cached_input_tokens=...) → span 一等字段 cachedInputTokens。"""
        self._init(flush_interval=0.05)
        with Trace(input="q") as tr:
            tr.llm("call", model="m", input="p", output="a",
                   input_tokens=1000, output_tokens=50, cached_input_tokens=800)
        flush()
        _, spans = self.server.requests[0]
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["inputTokens"], 1000)
        self.assertEqual(llm["cachedInputTokens"], 800)

    def test_batch_of_three_spans(self):
        self._init(flush_interval=0.05)
        with Trace(session_id="s1", user_id="u1", environment="staging",
                   input="问题") as tr:
            tr.llm("call_model", model="gpt-4o-mini", input="p", output="a",
                   input_tokens=10, output_tokens=5)
            tr.tool("query_db", input="sql", output="rows")
        flush()

        self.assertEqual(len(self.server.requests), 1)
        path, spans = self.server.requests[0]
        self.assertEqual(path, "/api/v1/ingest/events")
        self.assertEqual(len(spans), 3)
        ids = {s["spanId"] for s in spans}
        self.assertEqual(ids, {"root", spans[1]["spanId"], spans[2]["spanId"]})
        root = next(s for s in spans if s["spanId"] == "root")
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(root["agentKey"], "test-agent")
        self.assertEqual(root["status"], "OK")
        self.assertEqual(root["sessionId"], "s1")
        self.assertEqual(root["environment"], "staging")
        self.assertEqual(root["input"], "问题")
        self.assertEqual(llm["parentSpanId"], "root")
        self.assertEqual(llm["modelName"], "gpt-4o-mini")
        self.assertEqual(llm["inputTokens"], 10)
        self.assertEqual(llm["status"], "OK")

    def test_exception_marks_error_and_reraises(self):
        self._init()
        with self.assertRaises(ValueError):
            with Trace(input="x") as tr:
                tr.llm("call", model="m")
                raise ValueError("boom")
        flush()

        _, spans = self.server.requests[0]
        root = next(s for s in spans if s["spanId"] == "root")
        self.assertEqual(root["status"], "ERROR")
        self.assertEqual(root["errorType"], "ValueError")
        # 子 span 正常完成不受影响
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["status"], "OK")

    def test_noop_without_config(self):
        # 不 init 且无环境变量：get_client() 返回 no-op 客户端，Trace 可用、零请求、不抛错
        with Trace(input="x") as tr:
            tr.llm("call", model="m")
        c = get_client()
        self.assertIsNotNone(c)
        self.assertFalse(c.cfg.configured)
        self.assertEqual(len(self.server.requests), 0)

    def test_retry_on_500(self):
        self._init()
        self.server.fail_times = 1          # 第一次 500，重试成功
        with Trace(input="x") as tr:
            tr.tool("t", input="i", output="o")
        flush()

        self.assertGreaterEqual(len(self.server.requests), 2)   # 1 次失败 + 重试
        delivered = self.server.requests[-1][1]
        self.assertEqual(len(delivered), 2)

    def test_persistent_failure_drops_silently(self):
        self._init()
        self.server.fail_times = 99
        with Trace(input="x") as tr:
            tr.llm("call", model="m")
        flush()
        # 不崩、不抛错即为通过；请求次数 = 1 + 2 次重试
        self.assertEqual(len(self.server.requests), 3)

    def test_noop_mode_when_env_partial(self):
        # 只配 host 不配 key/agent → no-op
        init(host=self.host)
        with Trace(input="x") as tr:
            tr.llm("call", model="m")
        flush()
        self.assertEqual(len(self.server.requests), 0)


if __name__ == "__main__":
    unittest.main()
