"""emonlens-claude-setup + claude_hooks 桥接测试（含 transcript output 提取）。"""
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
from test_sdk import MockServer                                        # noqa: E402

from emonlens import claude_hooks, setup_agents                       # noqa: E402


class ClaudeSetupTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.settings_path = os.path.join(self.tmp, "settings.json")
        self.state_dir = os.path.join(self.tmp, "state")
        self._env = {}
        for k, v in [("EMONLENS_CONFIG", self.cfg_path),
                     ("EMONLENS_STATE_DIR", self.state_dir)]:
            self._env[k] = os.environ.pop(k, None)
            os.environ[k] = v

    def tearDown(self):
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        self.server.shutdown()

    def _all_spans(self):
        return [s for _, spans in self.server.requests for s in spans]

    def test_setup_writes_settings_and_config(self):
        setup_agents.main("claude", ["--host", "http://mock:8300", "--key", "tk-2",
                                     "--settings-path", self.settings_path,
                                     "--config-path", self.cfg_path])
        cfg = json.load(open(self.cfg_path))
        self.assertEqual(cfg["host"], "http://mock:8300")
        self.assertEqual(cfg["agent_claude"], "claude-code")
        settings = json.load(open(self.settings_path))
        for event in ["SessionStart", "UserPromptSubmit", "PostToolUse", "Stop"]:
            self.assertIn(event, settings["hooks"])

    def test_setup_preserves_existing_settings(self):
        with open(self.settings_path, "w") as f:
            json.dump({"hooks": {"Stop": [{"hooks": [
                {"type": "command", "command": "my-other-hook"}]}]}}, f)
        setup_agents.main("claude", ["--key", "tk-2",
                                     "--settings-path", self.settings_path,
                                     "--config-path", self.cfg_path])
        settings = json.load(open(self.settings_path))
        cmds = [h["command"] for blk in settings["hooks"]["Stop"] for h in blk["hooks"]]
        self.assertTrue(any("my-other-hook" in c for c in cmds))
        self.assertTrue(any("emonlens" in c for c in cmds))

    def test_bridge_reports_with_transcript_output(self):
        setup_agents.main("claude", ["--host",
                                     f"http://127.0.0.1:{self.server.server_address[1]}",
                                     "--key", "tk-2",
                                     "--settings-path", self.settings_path,
                                     "--config-path", self.cfg_path])
        sid = "claude-sim-1"
        # transcript：两轮助手消息，取最后一条
        transcript = os.path.join(self.tmp, "t.jsonl")
        rec = lambda text: json.dumps({"type": "assistant",
                                       "message": {"content": [{"type": "text", "text": text}]}})
        with open(transcript, "w") as f:
            f.write(rec("第一轮回答") + "\n")
            f.write(rec("最终回答：额度 已回复") + "\n")

        events = [("session_start", {"session_id": sid}),
                  ("user_prompt", {"session_id": sid, "prompt": "查工单进度"}),
                  ("post_tool_use", {"session_id": sid, "tool_name": "Bash",
                                     "tool_input": {"cmd": "ls"},
                                     "tool_response": "a.py"}),
                  ("stop", {"session_id": sid, "transcript_path": transcript})]
        for action, ev in events:
            with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
                claude_hooks.main([action])

        spans = self._all_spans()
        root = next(s for s in spans if s["parentSpanId"] is None)
        tool = next(s for s in spans if s["spanType"] == "tool")
        self.assertEqual(root["agentKey"], "claude-code")
        self.assertEqual(root["input"], "查工单进度")
        self.assertEqual(root["output"], "最终回答：额度 已回复")
        self.assertEqual(tool["spanName"], "Bash")
        self.assertEqual(tool["parentSpanId"], "root")


if __name__ == "__main__":
    unittest.main()
