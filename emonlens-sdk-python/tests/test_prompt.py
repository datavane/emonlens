"""Prompt 运行时拉取与 trace 关联测试（stdlib unittest，pytest 兼容）。

验收项：
1. get_prompt 命中服务端 → Prompt 对象（name/version/prompt/config/labels）
2. TTL 缓存：第二次同 (name, label) 不再发请求；force_refresh 绕过缓存
3. 服务端 404/失败 → 返回 None 不抛错
4. Trace(prompt=p) → 根 span attributes 自动带 prompt_name/prompt_version
5. Prompt.compile 渲染变量、未提供保留原样
"""
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from emonlens import Trace, flush, get_prompt, init
from emonlens.client import set_client


class MockServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.get_requests = []
        self.post_requests = []
        self.resolve_fail = False
        self.lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.lock:
            self.server.get_requests.append(self.path)
            failed = self.server.resolve_fail
        if failed:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'{"code":40401,"message":"not found"}')
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        body = json.dumps({"code": 0, "data": {
            "name": "support-assistant", "version": 2,
            "prompt": "你是客服助手。问题：{{question}}",
            "config": json.dumps({"model": "gpt-4o-mini"}),
            "labels": ["latest"],
        }}).encode()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"[]")
        with self.server.lock:
            self.server.post_requests.append((self.path, body))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"code":0,"data":{"accepted":%d}}' % len(body))

    def log_message(self, *a):
        pass


class PromptTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.host = f"http://127.0.0.1:{self.server.server_address[1]}"
        self._env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("EMONLENS")}
        init(host=self.host, ingest_key="tk-test", agent="test-agent")

    def tearDown(self):
        set_client(None)
        self.server.shutdown()
        for k, v in self._env.items():
            os.environ[k] = v

    def test_get_prompt_and_cache(self):
        p = get_prompt("support-assistant")
        self.assertIsNotNone(p)
        self.assertEqual(p.name, "support-assistant")
        self.assertEqual(p.version, 2)
        self.assertIn("{{question}}", p.prompt)
        gets = [r for r in self.server.get_requests if "resolve" in r]
        self.assertEqual(len(gets), 1)
        self.assertIn("label=production", gets[0])
        # 命中缓存：不再发请求
        p2 = get_prompt("support-assistant")
        self.assertIs(p, p2)
        gets2 = [r for r in self.server.get_requests if "resolve" in r]
        self.assertEqual(len(gets2), 1)
        # force_refresh 绕过缓存
        p3 = get_prompt("support-assistant", force_refresh=True)
        self.assertIsNotNone(p3)
        self.assertEqual(len([r for r in self.server.get_requests if "resolve" in r]), 2)

    def test_get_prompt_failure_returns_none(self):
        self.server.resolve_fail = True
        self.assertIsNone(get_prompt("missing"))
        self.assertIsNone(get_prompt("missing"))  # 失败不缓存

    def test_compile(self):
        p = get_prompt("support-assistant")
        self.assertEqual(p.compile(question="工单进度"), "你是客服助手。问题：工单进度")
        self.assertEqual(p.compile(), "你是客服助手。问题：{{question}}")

    def test_trace_prompt_linkage(self):
        p = get_prompt("support-assistant")
        with Trace(prompt=p, session_id="s9", input="q") as tr:
            tr.llm("call_model", input=p.compile(question="工单进度"), output="a")
        flush()
        path, spans = self.server.post_requests[-1]
        self.assertEqual(path, "/api/v1/ingest/events")
        root = next(s for s in spans if s["spanId"] == "root")
        attrs = root.get("attributes") or {}
        self.assertEqual(attrs.get("prompt_name"), "support-assistant")
        self.assertEqual(attrs.get("prompt_version"), "2")

    def test_unconfigured_returns_none(self):
        set_client(None)
        self.assertIsNone(get_prompt("anything"))


if __name__ == "__main__":
    unittest.main()
