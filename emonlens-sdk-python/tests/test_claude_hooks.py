"""Claude Code hooks 多轮对话测试（stdlib unittest，pytest 兼容）。

验收项：
1. 单轮：session_start → user_prompt → pre/post → stop，root input/output 正确
2. 多轮：同一 session 第二轮 user_prompt 产生新 traceId，root 不被幂等吞掉
3. tool span 归属当轮 trace
"""
import io
import json
import os
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

from emonlens import claude_hooks
from emonlens import setup_agents as setup_mod


class MockServer(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), Handler)
        self.requests = []

    def log_message(self, *a):
        pass


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"[]")
        with self.server.lock if hasattr(self.server, "lock") else threading.Lock():
            self.server.requests.append((self.path, body))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"code":0,"data":{"accepted":%d}}' % len(body))


class ClaudeHooksTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile_dir()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "settings.json")
        self._env = {k: os.environ.pop(k, None) for k in
                     ["EMONLENS_CONFIG", "EMONLENS_STATE_DIR", "EMONLENS_HOST"]}
        os.environ["EMONLENS_CONFIG"] = self.cfg_path
        os.environ["EMONLENS_STATE_DIR"] = os.path.join(self.tmp, "state")
        setup_mod.main("claude", ["--host", "http://127.0.0.1:%d" % self.server.server_address[1],
                                  "--key", "tk-cc", "--settings-path", self.hooks_path,
                                  "--config-path", self.cfg_path])

    def tearDown(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        self.server.shutdown()

    def _fire(self, action, ev):
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
            claude_hooks.main([action])

    def _spans(self):
        return [s for _, ss in self.server.requests for s in ss]

    def test_single_turn_root_io(self):
        sid = "cc-1"
        self._fire("session_start", {"session_id": sid})
        self._fire("user_prompt", {"session_id": sid, "prompt": "第一轮问题"})
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "Read",
                                     "tool_input": {"f": "a.py"}, "tool_response": "code"})
        self._fire("stop", {"session_id": sid, "transcript_path": "/nonexistent"})
        roots = [s for s in self._spans() if s["spanId"] == "root"]
        self.assertEqual(len(roots), 1)
        self.assertEqual(roots[0]["input"], "第一轮问题")
        tools = [s for s in self._spans() if s["spanType"] == "tool"]
        self.assertEqual(len(tools), 1)

    def test_multi_turn_new_trace_per_turn(self):
        sid = "cc-2"
        self._fire("user_prompt", {"session_id": sid, "prompt": "第一轮"})
        self._fire("stop", {"session_id": sid, "transcript_path": "/nonexistent"})
        self._fire("user_prompt", {"session_id": sid, "prompt": "第二轮"})
        self._fire("stop", {"session_id": sid, "transcript_path": "/nonexistent"})
        roots = [s for s in self._spans() if s["spanId"] == "root"]
        self.assertEqual(len(roots), 2, "两轮各应有一个 root")
        self.assertNotEqual(roots[0]["traceId"], roots[1]["traceId"])
        self.assertEqual(roots[0]["input"], "第一轮")
        self.assertEqual(roots[1]["input"], "第二轮")

    def test_transcript_model_extracted_to_llm_span(self):
        """Stop 事件从 transcript 提取 model：llm span 与 root 均带 modelName。"""
        sid = "cc-3"
        transcript = os.path.join(self.tmp, "t.jsonl")
        with open(transcript, "w") as f:
            f.write(json.dumps({"type": "assistant", "message": {
                "model": "claude-sonnet-4", "content": [{"type": "text", "text": "中间回答"}]}}) + "\n")
            f.write(json.dumps({"type": "assistant", "message": {
                "model": "claude-sonnet-4", "content": [{"type": "text", "text": "最终回答"}]}}) + "\n")
        self._fire("user_prompt", {"session_id": sid, "prompt": "问题"})
        self._fire("stop", {"session_id": sid, "transcript_path": transcript})
        llm = [s for s in self._spans() if s["spanType"] == "llm"]
        roots = [s for s in self._spans() if s["spanId"] == "root"]
        self.assertEqual(len(llm), 1)
        self.assertEqual(llm[0]["modelName"], "claude-sonnet-4")
        self.assertEqual(llm[0]["output"], "最终回答")
        self.assertEqual(llm[0]["input"], "问题")
        self.assertEqual(roots[0]["modelName"], "claude-sonnet-4")
        self.assertEqual(roots[0]["output"], "最终回答")


def tempfile_dir():
    import tempfile
    return tempfile.mkdtemp()


if __name__ == "__main__":
    unittest.main()
