"""P1 @observe / 集成测试（mock server 复用 test_sdk 的基类模式）。"""
import asyncio
import json
import os
import sys
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))          # 复用 test_sdk.MockServer
from test_sdk import MockServer                         # noqa: E402

from emonlens import (Trace, flush, init, observe, propagate,
                      set_session, trace_context, update_current)


def _all_spans(server):
    return [s for _, spans in server.requests for s in spans]


def _init_sdk(host):
    init(host=host, ingest_key="tk-test", agent="obs-agent")


class ObserveTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.host = f"http://127.0.0.1:{self.server.server_address[1]}"
        self._env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("EMONLENS")}

    def tearDown(self):
        flush()
        self.server.shutdown()
        for k, v in self._env.items():
            os.environ[k] = v

    def _init(self):
        _init_sdk(self.host)

    def test_nested_observe_tree(self):
        @observe(span_type="retrieval")
        def retrieve(q):
            return ["doc1", "doc2"]

        @observe(span_type="llm")
        def generate(q, docs):
            update_current(model="gpt-4o-mini", input_tokens=100, output_tokens=50)
            return "answer: " + q

        @observe()
        def answer_question(question):
            docs = retrieve(question)
            return generate(question, docs)

        self._init()
        result = answer_question("额度")
        flush()

        self.assertEqual(result, "answer: 额度")
        spans = _all_spans(self.server)
        self.assertEqual(len(spans), 3)
        by_name = {s["spanName"]: s for s in spans}
        root = by_name["answer_question"]
        self.assertIsNone(root["parentSpanId"])          # 根 = 无父
        self.assertEqual(root["spanType"], "agent")
        self.assertEqual(root["output"], "answer: 额度")
        self.assertEqual(root["sessionId"], None)
        # 嵌套挂树
        self.assertEqual(by_name["retrieve"]["parentSpanId"], root["spanId"])
        self.assertEqual(by_name["retrieve"]["spanType"], "retrieval")
        self.assertEqual(by_name["generate"]["parentSpanId"], root["spanId"])
        self.assertEqual(by_name["generate"]["spanType"], "llm")
        self.assertEqual(by_name["generate"]["modelName"], "gpt-4o-mini")
        self.assertEqual(by_name["generate"]["inputTokens"], 100)

    def test_observe_exception(self):
        @observe()
        def boom():
            raise RuntimeError("bad")

        self._init()
        with self.assertRaises(RuntimeError):
            boom()
        flush()

        _, spans = self.server.requests[-1]
        self.assertEqual(spans[0]["status"], "ERROR")
        self.assertEqual(spans[0]["errorType"], "RuntimeError")

    def test_observe_async(self):
        @observe()
        async def inner():
            return 42

        @observe()
        async def outer():
            return await inner()

        self._init()
        result = asyncio.run(outer())
        flush()

        self.assertEqual(result, 42)
        spans = _all_spans(self.server)
        self.assertEqual(len(spans), 2)
        outer = next(s for s in spans if s["parentSpanId"] is None)
        inner = next(s for s in spans if s["parentSpanId"] is not None)
        self.assertEqual(outer["spanName"], "outer")
        self.assertEqual(inner["spanName"], "inner")

    def test_trace_context_manager(self):
        @observe()
        def step():
            return 1

        self._init()
        with trace_context(session_id="c-9", name="pipeline"):
            step()
            step()
        flush()

        spans = _all_spans(self.server)
        root = next(s for s in spans if s["spanId"] == "root")
        self.assertEqual(root["spanName"], "pipeline")
        self.assertEqual(root["sessionId"], "c-9")
        steps = [s for s in spans if s["spanName"] == "step"]
        self.assertEqual(len(steps), 2)
        self.assertTrue(all(s["parentSpanId"] == "root" for s in steps))

    def test_thread_propagation(self):
        @observe()
        def worker():
            return "w"

        self._init()
        box = {}

        @observe()
        def main():
            def run_in_thread(captured, out):
                out["r"] = captured()
            target = propagate(worker)          # 在父线程捕获上下文
            out = {}
            t = threading.Thread(target=run_in_thread, args=(target, out))
            t.start()
            t.join()
            assert out["r"] == "w"

        main()
        flush()

        spans = _all_spans(self.server)
        main_span = next(s for s in spans if s["spanName"] == "main")
        child = next(s for s in spans if s["spanName"] == "worker")
        self.assertEqual(child["parentSpanId"], main_span["spanId"])   # 挂到主线程 Trace

    def test_noop_observe(self):
        @observe()
        def fn():
            return "ok"

        # 不 init
        self.assertEqual(fn(), "ok")
        self.assertEqual(len(self.server.requests), 0)


class OpenAIWrapTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.host = f"http://127.0.0.1:{self.server.server_address[1]}"
        self._env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("EMONLENS")}
        # 伪造 openai 模块（SDK 懒加载 import openai）
        class _Usage:
            prompt_tokens = 120
            completion_tokens = 30
        class _Msg:
            content = "模拟回答"
        class _Choice:
            message = _Msg()
        class _Resp:
            model = "gpt-4o-mini"
            usage = _Usage()
            choices = [_Choice()]
        class _Completions:
            def create(self, **kw):
                return _Resp()
        class _Chat:
            completions = _Completions()
        class FakeOpenAI:
            def __init__(self, *a, **k):
                self.chat = _Chat()
        self._fake = types_module(FakeOpenAI)

    def tearDown(self):
        flush()
        self.server.shutdown()
        for k, v in self._env.items():
            os.environ[k] = v
        sys.modules.pop("openai", None)

    def test_openai_wrapper_records_llm_span(self):
        _init_sdk(self.host)
        from emonlens.integrations.openai import OpenAI
        client = OpenAI(api_key="sk-fake")
        resp = client.chat.completions.create(
            model="gpt-4o-mini", messages=[{"role": "user", "content": "hi"}])
        self.assertEqual(resp.choices[0].message.content, "模拟回答")
        flush()

        spans = _all_spans(self.server)
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["modelName"], "gpt-4o-mini")
        self.assertEqual(llm["inputTokens"], 120)
        self.assertEqual(llm["outputTokens"], 30)
        self.assertEqual(llm["output"], "模拟回答")


def types_module(fake_cls):
    """注入伪造的 openai 模块。"""
    import types
    mod = types.ModuleType("openai")
    mod.OpenAI = fake_cls
    mod.AsyncOpenAI = fake_cls
    sys.modules["openai"] = mod
    return mod


    def test_trace_context_bridges_observe(self):
        """`with Trace(...)` 内调用 @observe 函数：挂到同一 trace（此前会自建孤儿 trace）。"""
        @observe(span_type="llm")
        def answer(q):
            update_current(model="m1", input_tokens=10, output_tokens=2)
            return "a:" + q

        self._init()
        with Trace(session_id="s-bridge", user_id="u-bridge", input="问题",
                   trace_id="fixed-trace-id") as tr:
            tr.tool("lookup", input="k", output="v")
            out = answer("额度")
        flush()

        spans = _all_spans(self.server)
        self.assertEqual(out, "a:额度")
        # root + tool + observe 的 llm，全部同一 traceId
        self.assertEqual(len(spans), 3)
        self.assertTrue(all(s["traceId"] == "fixed-trace-id" for s in spans), spans)
        root = next(s for s in spans if s["spanId"] == "root")
        tool = next(s for s in spans if s["spanName"] == "lookup")
        llm = next(s for s in spans if s["spanName"] == "answer")
        self.assertEqual(tool["parentSpanId"], "root")
        self.assertEqual(llm["parentSpanId"], "root")     # observe 挂到 Trace 根
        self.assertEqual(root["sessionId"], "s-bridge")
        self.assertEqual(llm["sessionId"], "s-bridge")
        self.assertEqual(llm["modelName"], "m1")
        self.assertEqual(llm["inputTokens"], 10)
        self.assertEqual(llm["spanType"], "llm")


if __name__ == "__main__":
    unittest.main()
