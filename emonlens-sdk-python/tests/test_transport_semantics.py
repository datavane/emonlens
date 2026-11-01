"""post_spans 传输层语义测试（stdlib unittest，pytest 兼容）。

验收项：
1. 200 + rejected[]：不抛错、返回 True、打印拒收摘要（避免数据静默丢失）
2. 400（批次非法）：fail-fast 不重试（重试同样失败）
"""
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

from emonlens.transport import post_spans
from emonlens.config import Config


class CfgServer(ThreadingHTTPServer):
    """可编程响应的 mock：mode 决定返回什么。"""

    def __init__(self):
        super().__init__(("127.0.0.1", 0), CfgHandler)
        self.mode = "ok"            # ok | rejected | bad400
        self.post_count = 0
        self.lock = threading.Lock()

    def log_message(self, *a):
        pass


class CfgHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        srv = self.server
        with srv.lock:
            srv.post_count += 1
            mode, count = srv.mode, srv.post_count
        if mode == "serverdropped":
            body = json.dumps({"code": 0, "data": {
                "acceptedCount": 2, "rejected": [], "droppedInRequest": 3}}).encode()
            code = 200
        elif mode == "rejected":
            body = json.dumps({"code": 0, "data": {
                "acceptedCount": 1,
                "rejected": [{"index": 1, "reason": "spanType must be one of [agent, llm, tool, retrieval]"}],
            }}).encode()
            code = 200
        elif mode == "business401":
            body = json.dumps({"code": 40101, "message": "invalid or revoked ingest key"}).encode()
            code = 200
        elif mode == "business400":
            body = json.dumps({"code": 40000, "message": "traceId must be <= 64 chars"}).encode()
            code = 200
        elif mode == "business503":
            body = json.dumps({"code": 50301, "message": "rate limited"}).encode()
            code = 200
        elif mode == "bad400":
            body = b'{"code":40000,"message":"batch too large: 2000 spans"}'
            code = 400
        else:
            body = b'{"code":0,"data":{"acceptedCount":2,"rejected":[]}}'
            code = 200
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)


class PostSpansSemanticsTest(unittest.TestCase):
    def setUp(self):
        self.server = CfgServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.cfg = Config(host=f"http://127.0.0.1:{self.server.server_address[1]}",
                                ingest_key="tk-test", agent="t", agent_version="v1",
                                environment="test")
        self.stdout_backup = sys.stdout
        self.stderr_backup = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()   # SDK 运营提示走 stderr（不污染被消费的 stdout）

    def tearDown(self):
        sys.stdout = self.stdout_backup
        sys.stderr = self.stderr_backup
        self.server.shutdown()

    def _spans(self):
        return [{"traceId": "t1", "spanId": "a", "agentKey": "x", "spanType": "agent",
                 "spanName": "op", "startTime": "2026-10-03T10:00:00"},
                {"traceId": "t1", "spanId": "b", "agentKey": "x", "spanType": "llm",
                 "spanName": "call", "startTime": "2026-10-03T10:00:01"}]

    def test_partial_rejection_warns_and_succeeds(self):
        self.server.mode = "rejected"
        ok = post_spans(self.cfg, self._spans())
        self.assertTrue(ok)                                   # 有 accepted 即成功
        out = sys.stderr.getvalue()
        self.assertIn("被服务端拒收", out)
        self.assertIn("spanType", out)                        # 摘要含首个原因

    def test_server_dropped_warns(self):
        self.server.mode = "serverdropped"
        ok = post_spans(self.cfg, self._spans())
        self.assertTrue(ok)
        out = sys.stderr.getvalue()
        self.assertIn("服务端队列打满", out)
        self.assertIn("丢弃 3 spans", out)

    def test_400_fails_fast_without_retry(self):
        self.server.mode = "bad400"
        ok = post_spans(self.cfg, self._spans())
        self.assertFalse(ok)
        self.assertEqual(self.server.post_count, 1)           # 不重试
        out = sys.stderr.getvalue()
        self.assertIn("批次被拒收（400）", out)

    def test_business_401_stops_without_retry(self):
        # 服务端鉴权失败是 HTTP 200 + ApiResult.code=40101：不能当成功
        import emonlens.transport as transport
        self.server.mode = "business401"
        with mock.patch.object(transport, "_WARNED_401", threading.Event()):
            ok = post_spans(self.cfg, self._spans())
        self.assertFalse(ok)
        self.assertEqual(self.server.post_count, 1)
        self.assertIn("Ingest Key 无效", sys.stderr.getvalue())

    def test_business_400_fails_fast_without_retry(self):
        self.server.mode = "business400"
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"EMONLENS_STATE_DIR": tmp}):
                ok = post_spans(self.cfg, self._spans())
        self.assertFalse(ok)
        self.assertEqual(self.server.post_count, 1)
        self.assertIn("被服务端拒收", sys.stderr.getvalue())

    def test_business_503_retries_then_falls_back(self):
        self.server.mode = "business503"
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"EMONLENS_STATE_DIR": tmp}):
                with mock.patch("emonlens.transport.time.sleep"):
                    ok = post_spans(self.cfg, self._spans())
        self.assertFalse(ok)
        self.assertEqual(self.server.post_count, 1 + 2)       # 2 次重试
        self.assertIn("服务端限流", sys.stderr.getvalue())


class ConfigParsingTest(unittest.TestCase):
    def test_bad_numeric_env_falls_back(self):
        with mock.patch.dict(os.environ, {
                "EMONLENS_FLUSH_INTERVAL": "abc", "EMONLENS_MAX_BATCH": "x"}):
            cfg = Config.from_env()
        self.assertEqual(cfg.flush_interval, 2.0)
        self.assertEqual(cfg.max_batch, 100)

    def test_max_batch_is_clamped_to_server_limit(self):
        with mock.patch.dict(os.environ, {"EMONLENS_MAX_BATCH": "9999"}):
            cfg = Config.from_env()
        self.assertEqual(cfg.max_batch, 500)


if __name__ == "__main__":
    unittest.main()
