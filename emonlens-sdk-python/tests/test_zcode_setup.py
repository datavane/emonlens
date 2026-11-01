"""emonlens-zcode-setup + zcode_hooks 桥接测试。

验收项：
1. ZCode 配置格式（hooks.enabled + hooks.events），保留顶层配置与已有钩子、幂等
2. matcher 省略（ZCode 的 matcher 是正则，`*` 非法永不匹配）
3. turnId 分组：同轮 tool / llm / root 同 trace，多轮各自成 trace，子 agent 工具挂主轮次
4. PostToolUseFailure → ERROR tool span
5. Stop 从 ZCode rollout 聚合本轮 token（含缓存命中/推理 tokens）
"""
import io
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
from test_sdk import MockServer                                    # noqa: E402

from emonlens import setup_agents as setup_mod                      # noqa: E402
from emonlens import zcode_hooks                                    # noqa: E402


class ZCodeSetupTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "zcode-config.json")
        self.state_dir = os.path.join(self.tmp, "state")
        self.rollout_dir = os.path.join(self.tmp, "rollout")
        os.makedirs(self.rollout_dir)
        self._env = {k: os.environ.pop(k) for k in list(os.environ)
                     if k.startswith("EMONLENS")}
        os.environ["EMONLENS_CONFIG"] = self.cfg_path
        os.environ["EMONLENS_STATE_DIR"] = self.state_dir
        os.environ["EMONLENS_ZCODE_ROLLOUT_DIR"] = self.rollout_dir

    def tearDown(self):
        for k in list(os.environ):
            if k.startswith("EMONLENS"):
                os.environ.pop(k, None)
        os.environ.update(self._env)
        self.server.shutdown()

    def _host(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    def _setup(self, host=None, key="tk-zcode"):
        setup_mod.main("zcode", ["--host", host or self._host(), "--key", key,
                                 "--settings-path", self.hooks_path,
                                 "--config-path", self.cfg_path])

    def _fire(self, action, ev):
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
            zcode_hooks.main([action])

    def _spans(self):
        return [s for _, ss in self.server.requests for s in ss]

    def _zcode_config(self):
        with open(self.hooks_path) as f:
            return json.load(f)

    def _commands(self, event):
        cfg = self._zcode_config()
        return [h["command"] for blk in cfg["hooks"]["events"][event]
                for h in blk.get("hooks", [])]

    def test_setup_writes_zcode_format_and_agent_config(self):
        self._setup()
        cfg = json.load(open(self.cfg_path))
        self.assertEqual(cfg["host"], self._host())
        self.assertEqual(cfg["ingest_key"], "tk-zcode")
        self.assertEqual(cfg["agent_zcode"], "zcode")

        hooks = self._zcode_config()["hooks"]
        self.assertIs(hooks["enabled"], True)
        for event, action in [("SessionStart", "session_start"),
                              ("UserPromptSubmit", "user_prompt"),
                              ("PreToolUse", "pre_tool_use"),
                              ("PostToolUse", "post_tool_use"),
                              ("PostToolUseFailure", "post_tool_use_failure"),
                              ("Stop", "stop")]:
            self.assertIn(event, hooks["events"])
            self.assertTrue(any(action in c for c in self._commands(event)), event)
            # ZCode matcher 是大小写敏感正则：省略表示全匹配，`*` 是非法正则
            for block in hooks["events"][event]:
                self.assertNotIn("matcher", block)

    def test_setup_preserves_existing_config_and_hooks(self):
        with open(self.hooks_path, "w") as f:
            json.dump({"plugins": {"enabledPlugins": {"x@y": True}},
                       "hooks": {"enabled": True, "events": {"Stop": [
                           {"hooks": [{"type": "command", "command": "my-other-hook"}]}]}}}, f)
        self._setup()
        cfg = self._zcode_config()
        self.assertEqual(cfg["plugins"], {"enabledPlugins": {"x@y": True}})
        stop_cmds = self._commands("Stop")
        self.assertTrue(any("my-other-hook" in c for c in stop_cmds))     # 保留
        self.assertTrue(any("emonlens" in c for c in stop_cmds))          # 追加

    def test_setup_is_idempotent(self):
        self._setup()
        self._setup()
        for event in ("SessionStart", "UserPromptSubmit", "PreToolUse",
                      "PostToolUse", "PostToolUseFailure", "Stop"):
            self.assertEqual(
                len([c for c in self._commands(event) if "emonlens" in c]), 1, event)

    def test_bridge_reports_turn_with_turnid_grouping(self):
        self._setup()
        sid, turn = "session_zc-1", "turn-zc-1"
        self._fire("session_start", {"session_id": sid, "source": "startup",
                                     "model": "prov/model-z"})
        self._fire("user_prompt", {"session_id": sid, "turnId": turn, "prompt": "查工单进度"})
        self._fire("pre_tool_use", {"session_id": sid, "turnId": turn,
                                    "tool_name": "Bash", "tool_input": {"command": "ls"}})
        time.sleep(0.3)
        self._fire("post_tool_use", {"session_id": sid, "turnId": turn,
                                     "tool_name": "Bash", "tool_input": {"command": "ls"},
                                     "tool_response": "a.py"})
        self._fire("stop", {"session_id": sid, "turnId": turn,
                            "last_assistant_message": "工单已进入二线处理"})

        spans = self._spans()
        self.assertEqual(sorted(s["spanType"] for s in spans), ["agent", "llm", "tool"])
        self.assertEqual(len({s["traceId"] for s in spans}), 1)
        root = next(s for s in spans if s["spanType"] == "agent")
        tool = next(s for s in spans if s["spanType"] == "tool")
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(root["spanName"], "zcode_session")
        self.assertEqual(root["agentKey"], "zcode")
        self.assertEqual(root["input"], "查工单进度")
        self.assertEqual(root["output"], "工单已进入二线处理")
        self.assertEqual(root["modelName"], "prov/model-z")
        self.assertEqual(llm["modelName"], "prov/model-z")
        self.assertEqual(tool["spanName"], "Bash")
        self.assertEqual(tool["parentSpanId"], "root")
        self.assertGreaterEqual(tool["durationMs"], 250)
        self.assertTrue(all(s.get("sessionId") == sid for s in spans), spans)
        self.assertEqual(tool["attributes"]["turnId"], turn)
        # stop 后本轮状态清理
        self.assertFalse(os.path.exists(os.path.join(self.state_dir, sid + ".json")))

    def test_multi_turn_gets_separate_traces(self):
        self._setup()
        sid = "session_zc-2"
        for turn, prompt in [("T1", "第一轮"), ("T2", "第二轮")]:
            self._fire("user_prompt", {"session_id": sid, "turnId": turn, "prompt": prompt})
            self._fire("stop", {"session_id": sid, "turnId": turn,
                                "last_assistant_message": "答-" + prompt})
        roots = [s for s in self._spans() if s["spanType"] == "agent"]
        self.assertEqual(len(roots), 2)
        self.assertEqual(len({r["traceId"] for r in roots}), 2)
        self.assertEqual({r["input"] for r in roots}, {"第一轮", "第二轮"})

    def test_subagent_tool_joins_main_turn_trace(self):
        """子 agent 工具事件带自己的 turnId：挂到主轮次，避免孤儿 trace。"""
        self._setup()
        sid = "session_zc-3"
        self._fire("user_prompt", {"session_id": sid, "turnId": "MAIN", "prompt": "改 Bug"})
        self._fire("post_tool_use", {"session_id": sid, "turnId": "SUB-1",
                                     "tool_name": "Read", "tool_response": "ok"})
        self._fire("stop", {"session_id": sid, "turnId": "MAIN",
                            "last_assistant_message": "完成"})
        spans = self._spans()
        self.assertEqual(len({s["traceId"] for s in spans}), 1, spans)
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["input"], "改 Bug")

    def test_tool_failure_reports_error_span(self):
        self._setup()
        sid, turn = "session_zc-4", "turn-fail"
        self._fire("user_prompt", {"session_id": sid, "turnId": turn, "prompt": "跑测试"})
        self._fire("post_tool_use_failure", {
            "session_id": sid, "turnId": turn, "tool_name": "Bash",
            "tool_input": {"command": "pytest"}, "error": "exit code 1",
            "error_details": {"message": "exit code 1"}})
        self._fire("stop", {"session_id": sid, "turnId": turn,
                            "last_assistant_message": "测试失败"})
        tool = next(s for s in self._spans() if s["spanType"] == "tool")
        self.assertEqual(tool["status"], "ERROR")
        self.assertEqual(tool["errorType"], "exit code 1")
        self.assertIn("exit code 1", tool["output"])

    def _write_rollout(self, sid, records):
        path = os.path.join(self.rollout_dir, "model-io-%s.jsonl" % sid)
        with open(path, "w") as f:
            for rec in records:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        return path

    def test_stop_aggregates_rollout_token_usage(self):
        self._setup()
        sid, turn = "session_zc-5", "turn-tok"
        model = {"providerId": "prov", "modelId": "model-z"}
        self._write_rollout(sid, [
            {"turnId": turn, "model": model, "querySource": "main_turn",
             "response": {"usage": {"inputTokens": 100, "outputTokens": 10,
                                    "cacheReadTokens": 60, "reasoningTokens": 1,
                                    "totalTokens": 110}}},
            {"turnId": "other-turn", "model": {"providerId": "prov", "modelId": "x"},
             "response": {"usage": {"inputTokens": 9999, "outputTokens": 9999}}},
            {"turnId": turn, "model": model, "querySource": "main_turn",
             "response": {"usage": {"inputTokens": 200, "outputTokens": 20,
                                    "cacheReadTokens": 150, "reasoningTokens": 2,
                                    "totalTokens": 220}}},
        ])
        self._fire("user_prompt", {"session_id": sid, "turnId": turn, "prompt": "分析"})
        self._fire("stop", {"session_id": sid, "turnId": turn,
                            "last_assistant_message": "完成"})
        spans = self._spans()
        llm = next(s for s in spans if s["spanType"] == "llm")
        root = next(s for s in spans if s["spanType"] == "agent")
        self.assertEqual(llm["inputTokens"], 300)
        self.assertEqual(llm["outputTokens"], 30)
        self.assertEqual(llm["cachedInputTokens"], 210)
        self.assertEqual(llm["attributes"]["reasoningOutputTokens"], 3)
        self.assertEqual(llm["attributes"]["turnTotalTokens"], 330)
        self.assertEqual(llm["modelName"], "prov/model-z")
        self.assertEqual(root["modelName"], "prov/model-z")

    def test_stop_without_rollout_omits_usage(self):
        self._setup()
        sid = "session_zc-6"
        self._fire("user_prompt", {"session_id": sid, "turnId": "T", "prompt": "hi"})
        self._fire("stop", {"session_id": sid, "turnId": "T",
                            "last_assistant_message": "ok"})
        llm = next(s for s in self._spans() if s["spanType"] == "llm")
        self.assertNotIn("inputTokens", llm)      # 读不到时不写 0，避免污染统计


if __name__ == "__main__":
    unittest.main()
