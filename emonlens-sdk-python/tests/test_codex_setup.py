"""emonlens-codex-setup + codex_hooks 桥接测试（临时目录隔离）。"""
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

from emonlens import codex_hooks
from emonlens import setup_agents as setup_mod                      # noqa: E402


class CodexSetupTest(unittest.TestCase):
    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "hooks.json")
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

    def _cfg(self):
        return json.load(open(self.cfg_path))

    def test_setup_writes_config_and_registers_hooks(self):
        setup_mod.main("codex", ["--host", "http://mock:8300", "--key", "tk-1",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])
        cfg = self._cfg()
        self.assertEqual(cfg["host"], "http://mock:8300")
        self.assertEqual(cfg["ingest_key"], "tk-1")
        self.assertEqual(cfg["agent_codex"], "codex-cli")
        with open(self.hooks_path) as f:
            hooks = json.load(f)
        for event, action in [("SessionStart", "session_start"),
                              ("UserPromptSubmit", "user_prompt"),
                              ("PostToolUse", "post_tool_use"),
                              ("Stop", "stop")]:
            cmds = [h["command"] for blk in hooks["hooks"][event] for h in blk["hooks"]]
            self.assertTrue(any(action in c for c in cmds), event)

    def test_setup_preserves_existing_hooks(self):
        with open(self.hooks_path, "w") as f:
            json.dump({"hooks": {"Stop": [{"hooks": [
                {"type": "command", "command": "my-existing-hook"}]}]}}, f)
        setup_mod.main("codex", ["--host", "http://mock:8300", "--key", "tk-1",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])
        with open(self.hooks_path) as f:
            hooks = json.load(f)
        stop_cmds = [h["command"] for blk in hooks["hooks"]["Stop"] for h in blk["hooks"]]
        self.assertTrue(any("my-existing-hook" in c for c in stop_cmds))   # 保留
        self.assertTrue(any("emonlens" in c for c in stop_cmds))           # 追加

    def test_setup_is_idempotent(self):
        for _ in range(2):
            setup_mod.main("codex", ["--host", "http://mock:8300", "--key", "tk-1",
                              "--settings-path", self.hooks_path,
                              "--config-path", self.cfg_path])
        with open(self.hooks_path) as f:
            hooks = json.load(f)
        stop_cmds = [h["command"] for blk in hooks["hooks"]["Stop"] for h in blk["hooks"]]
        self.assertEqual(len([c for c in stop_cmds if "emonlens" in c]), 1)  # 不重复注册

    def test_hook_bridge_reports_session_events(self):
        # 指向 mock server 并模拟完整事件流
        self._env["EMONLENS_HOST"] = os.environ.pop("EMONLENS_HOST", None)  # 泄漏防护
        os.environ["EMONLENS_HOST"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        setup_mod.main("codex", ["--host", os.environ["EMONLENS_HOST"], "--key", "tk-1",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])
        sid = "sim-sess"
        events = [("session_start", {"session_id": sid}),
                  ("user_prompt", {"session_id": sid, "prompt": "查额度"}),
                  ("post_tool_use", {"session_id": sid, "tool_name": "query_db",
                                     "tool_input": {"id": 1}, "tool_response": "ok"}),
                  ("stop", {"session_id": sid, "last_assistant_message": "完成"})]
        for action, ev in events:
            with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
                codex_hooks.main([action])
        flush_spans = []
        for path, spans in self.server.requests:
            flush_spans.extend(spans)
        self.assertEqual(len(flush_spans), 3)          # tool 子 span + llm 轮次 span + 根 span
        root = next(s for s in flush_spans if s["parentSpanId"] is None)
        tool = next(s for s in flush_spans if s["parentSpanId"] == "root"
                    and s["spanType"] == "tool")
        llm = next(s for s in flush_spans if s["spanType"] == "llm")
        self.assertEqual(root["spanName"], "codex_session")
        self.assertEqual(root["input"], "查额度")
        self.assertEqual(root["output"], "完成")
        self.assertEqual(tool["spanName"], "query_db")
        self.assertEqual(tool["agentKey"], "codex-cli")
        # 会话归属：全部 span 带 sessionId（否则服务端无法聚合，会话页恒空）
        self.assertTrue(all(s.get("sessionId") == "sim-sess" for s in flush_spans), flush_spans)
        # llm 轮次 span：无 model 事件时不带 modelName，但输入/输出齐全（供列表摘要）
        self.assertEqual(llm["spanName"], "assistant_turn")
        self.assertEqual(llm["input"], "查额度")
        self.assertEqual(llm["output"], "完成")
        self.assertNotIn("modelName", llm)
        # stop 后状态清理
        self.assertFalse(os.path.exists(os.path.join(
            self.state_dir, sid + ".json")))

    def test_hook_bridge_carries_optional_user(self):
        """EMONLENS_USER 环境变量（或 config user 字段）→ 全部 span 带 userId（用户洞察聚合）。"""
        os.environ["EMONLENS_USER"] = "insight-user"
        try:
            self._env["EMONLENS_HOST"] = os.environ.pop("EMONLENS_HOST", None)
            os.environ["EMONLENS_HOST"] = f"http://127.0.0.1:{self.server.server_address[1]}"
            setup_mod.main("codex", ["--host", os.environ["EMONLENS_HOST"], "--key", "tk-1u",
                              "--settings-path", self.hooks_path,
                              "--config-path", self.cfg_path])
            sid = "user-sess"
            for action, ev in [("user_prompt", {"session_id": sid, "prompt": "问"}),
                               ("post_tool_use", {"session_id": sid, "tool_name": "Bash"}),
                               ("stop", {"session_id": sid, "last_assistant_message": "答"})]:
                with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
                    codex_hooks.main([action])
            spans = [s for _, ss in self.server.requests for s in ss]
            self.assertTrue(len(spans) >= 3)
            self.assertTrue(all(s.get("userId") == "insight-user" for s in spans), spans)
            self.assertTrue(all(s.get("sessionId") == sid for s in spans))
        finally:
            os.environ.pop("EMONLENS_USER", None)

    def test_hook_bridge_captures_model_and_llm_span(self):
        """事件带 model 时：llm span 与根 span 均携带 modelName（列表模型列数据源）。"""
        self._env["EMONLENS_HOST"] = os.environ.pop("EMONLENS_HOST", None)
        os.environ["EMONLENS_HOST"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        setup_mod.main("codex", ["--host", os.environ["EMONLENS_HOST"], "--key", "tk-1",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])
        sid = "model-sess"
        events = [("user_prompt", {"session_id": sid, "prompt": "分析风险", "model": "gpt-6-sol"}),
                  ("pre_tool_use", {"session_id": sid, "tool_name": "Bash",
                                    "tool_input": "ls", "model": "gpt-6-sol"}),
                  ("post_tool_use", {"session_id": sid, "tool_name": "Bash",
                                     "tool_input": "ls", "tool_response": "ok"}),
                  ("stop", {"session_id": sid, "last_assistant_message": "风险为 HIGH",
                            "model": "gpt-6-sol"})]
        for action, ev in events:
            with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
                codex_hooks.main([action])
        flush_spans = []
        for path, spans in self.server.requests:
            flush_spans.extend(spans)
        root = next(s for s in flush_spans if s["parentSpanId"] is None)
        llm = next(s for s in flush_spans if s["spanType"] == "llm")
        tool = next(s for s in flush_spans if s["spanType"] == "tool")
        self.assertEqual(llm["modelName"], "gpt-6-sol")
        self.assertEqual(llm["input"], "分析风险")
        self.assertEqual(llm["output"], "风险为 HIGH")
        self.assertEqual(llm["parentSpanId"], "root")
        self.assertEqual(root["modelName"], "gpt-6-sol")
        # tool span 同样携带 model（事件里有 model 时随 span 上报，模型用量/筛选可见）
        self.assertEqual(tool["modelName"], "gpt-6-sol")


class CodexTokenUsageTest(unittest.TestCase):
    """stop 阶段从 rollout jsonl 读取 token 用量（turn 增量）并写入 llm span。"""

    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "hooks.json")
        os.environ["EMONLENS_CONFIG"] = self.cfg_path
        os.environ["EMONLENS_STATE_DIR"] = os.path.join(self.tmp, "state")
        self._host = os.environ.pop("EMONLENS_HOST", None)
        os.environ["EMONLENS_HOST"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        setup_mod.main("codex", ["--host", os.environ["EMONLENS_HOST"], "--key", "tk-4",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])

    def tearDown(self):
        self.server.shutdown()
        for k in ["EMONLENS_CONFIG", "EMONLENS_STATE_DIR", "EMONLENS_HOST"]:
            os.environ.pop(k, None)
        if self._host is not None:
            os.environ["EMONLENS_HOST"] = self._host

    def _rollout(self, entries, tail_junk=b""):
        """构造 rollout jsonl：一行一条 event_msg；tail_junk 模拟写入中断的半行。"""
        path = os.path.join(self.tmp, "rollout.jsonl")
        with open(path, "wb") as f:
            for e in entries:
                f.write(json.dumps(e, ensure_ascii=False).encode("utf-8") + b"\n")
            f.write(tail_junk)
        return path

    @staticmethod
    def _token_count(inp, cached, out, reasoning):
        return {"type": "event_msg", "payload": {"type": "token_count", "info": {
            "total_token_usage": {"input_tokens": inp * 10, "output_tokens": out * 10},
            "last_token_usage": {"input_tokens": inp, "cached_input_tokens": cached,
                                 "output_tokens": out, "reasoning_output_tokens": reasoning,
                                 "total_tokens": inp + out}}}}

    def _fire(self, action, ev):
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
            codex_hooks.main([action])

    def test_stop_reads_last_token_usage_from_rollout(self):
        sid = "tok-1"
        rollout = self._rollout([
            {"type": "event_msg", "payload": {"type": "other"}},
            self._token_count(100, 60, 20, 5),      # 更早的轮次
            self._token_count(98431, 98048, 247, 200),  # 最新增量（应取这条）
            {"type": "event_msg", "payload": {"type": "token_count"}},  # 无 info：跳过
        ], tail_junk=b'{"type":"event_msg","payload":{"type":"token_cou')  # 半行残尾
        self._fire("user_prompt", {"session_id": sid, "prompt": "分析", "model": "gpt-6-luna"})
        self._fire("stop", {"session_id": sid, "turn_id": "turn-abc",
                            "last_assistant_message": "完成", "model": "gpt-6-luna",
                            "transcript_path": rollout})
        spans = [s for _, ss in self.server.requests for s in ss]
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["inputTokens"], 98431)
        self.assertEqual(llm["outputTokens"], 247)
        self.assertEqual(llm["modelName"], "gpt-6-luna")
        self.assertEqual(llm["cachedInputTokens"], 98048)      # 一等字段（服务端独立列）
        self.assertEqual(llm["attributes"]["reasoningOutputTokens"], 200)

    def test_stop_without_transcript_still_reports(self):
        sid = "tok-2"
        self._fire("user_prompt", {"session_id": sid, "prompt": "hi"})
        self._fire("stop", {"session_id": sid, "last_assistant_message": "ok",
                            "transcript_path": os.path.join(self.tmp, "missing.jsonl")})
        spans = [s for _, ss in self.server.requests for s in ss]
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertNotIn("inputTokens", llm)      # 读不到时不写 0，避免污染统计

    def test_read_last_usage_handles_broken_tail(self):
        path = self._rollout([self._token_count(7, 1, 3, 0)],
                             tail_junk=b'{"type":"event_msg","payload":{"type":"token_cou')
        usage = codex_hooks._read_last_usage(path)
        self.assertEqual(usage["input_tokens"], 7)
        self.assertIsNone(codex_hooks._read_last_usage(os.path.join(self.tmp, "nope.jsonl")))
        self.assertIsNone(codex_hooks._read_last_usage(""))

    def test_stop_span_id_is_stable_per_turn(self):
        """同一 turn 的 stop 重复触发（Codex 重试）：llm span 幂等，服务端可按 spanId 去重。"""
        sid = "tok-3"
        stop_ev = {"session_id": sid, "turn_id": "turn-stable",
                   "last_assistant_message": "完成"}
        for _ in range(2):
            self._fire("user_prompt", {"session_id": sid, "prompt": "q"})
            self._fire("stop", stop_ev)
        spans = [s for _, ss in self.server.requests for s in ss]
        llm_ids = [s["spanId"] for s in spans if s["spanType"] == "llm"]
        self.assertEqual(len(llm_ids), 2)
        self.assertEqual(llm_ids[0], llm_ids[1])


if __name__ == "__main__":
    unittest.main()


class ToolDurationPairingTest(unittest.TestCase):
    """PreToolUse→PostToolUse 配对计算真实耗时 + 懒创建。"""

    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "hooks.json")
        os.environ["EMONLENS_CONFIG"] = self.cfg_path
        os.environ["EMONLENS_STATE_DIR"] = os.path.join(self.tmp, "state")
        setup_mod.main("codex", ["--host",
                          f"http://127.0.0.1:{self.server.server_address[1]}",
                          "--key", "tk-3", "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])
        # 无 session_start：验证懒创建

    def tearDown(self):
        self.server.shutdown()
        for k in ["EMONLENS_CONFIG", "EMONLENS_STATE_DIR"]:
            os.environ.pop(k, None)

    def _fire(self, action, ev):
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
            codex_hooks.main([action])

    def test_pairing_computes_real_duration(self):
        sid = "pair-1"
        self._fire("pre_tool_use", {"session_id": sid, "tool_name": "Bash"})
        time.sleep(0.35)                                   # 模拟工具执行 350ms
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "Bash",
                                     "tool_input": {"cmd": "ls"}, "tool_response": "ok"})
        spans = [s for _, ss in self.server.requests for s in ss]
        tool = next(s for s in spans if s["spanType"] == "tool")
        self.assertGreaterEqual(tool["durationMs"], 300)   # 真实耗时而非 0ms
        self.assertEqual(tool["startTime"][:10], time.strftime("%Y-%m-%d"))
        # 懒创建的 trace 也入库
        self.assertTrue(tool["traceId"].startswith("codex-"))

    def test_pairing_matches_same_name(self):
        sid = "pair-2"
        self._fire("pre_tool_use", {"session_id": sid, "tool_name": "A"})
        self._fire("pre_tool_use", {"session_id": sid, "tool_name": "B"})
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "B"})   # 后进先配
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "A"})
        spans = [s for _, ss in self.server.requests for s in ss]
        tools = [s for s in spans if s["spanType"] == "tool"]
        self.assertEqual(len(tools), 2)
        a = next(t for t in tools if t["toolName"] == "A")
        b = next(t for t in tools if t["toolName"] == "B")
        # A 的开始早于 B：A 的 duration 应明显大于 B（由配对而非顺序决定）
        self.assertGreaterEqual(a["durationMs"], b["durationMs"])


class CodexTraceGroupingTest(unittest.TestCase):
    """同一轮次的 tool / llm / root span 必须落在同一 traceId。

    traceId 由 turn_id 派生（而非 state 里的随机后缀），否则：
      - SessionStart 在 resume/compact 时再次触发，覆盖 state.traceId → trace 碎片化
      - 主/子 agent 共享同一 session 的 state 互相覆盖 → 同上
    """

    def setUp(self):
        self.server = MockServer()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.mkdtemp()
        self.cfg_path = os.path.join(self.tmp, "config.json")
        self.hooks_path = os.path.join(self.tmp, "hooks.json")
        self.state_dir = os.path.join(self.tmp, "state")
        os.environ["EMONLENS_CONFIG"] = self.cfg_path
        os.environ["EMONLENS_STATE_DIR"] = self.state_dir
        self._host = os.environ.pop("EMONLENS_HOST", None)
        os.environ["EMONLENS_HOST"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        setup_mod.main("codex", ["--host", os.environ["EMONLENS_HOST"], "--key", "tk-g",
                          "--settings-path", self.hooks_path,
                          "--config-path", self.cfg_path])

    def tearDown(self):
        self.server.shutdown()
        for k in ["EMONLENS_CONFIG", "EMONLENS_STATE_DIR", "EMONLENS_HOST"]:
            os.environ.pop(k, None)
        if self._host is not None:
            os.environ["EMONLENS_HOST"] = self._host

    def _fire(self, action, ev):
        with mock.patch.object(sys, "stdin", io.StringIO(json.dumps(ev))):
            codex_hooks.main([action])

    def _spans(self):
        out = []
        for _, ss in self.server.requests:
            out.extend(ss)
        return out

    def test_session_start_resume_does_not_fragment_trace(self):
        sid = "grp-1"
        self._fire("user_prompt", {"session_id": sid, "turn_id": "T1", "prompt": "查额度"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "T1", "tool_name": "Bash",
                                     "tool_input": "ls", "tool_response": "ok"})
        self._fire("session_start", {"session_id": sid, "source": "compact"})   # 压缩：再次触发
        self._fire("pre_tool_use", {"session_id": sid, "turn_id": "T1", "tool_name": "Bash"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "T1", "tool_name": "Bash",
                                     "tool_input": "ls", "tool_response": "ok"})
        self._fire("stop", {"session_id": sid, "turn_id": "T1", "last_assistant_message": "答"})
        spans = self._spans()
        tids = {s["traceId"] for s in spans}
        self.assertEqual(len(tids), 1, f"同一轮次被拆成多条 trace: {tids}")
        self.assertEqual(len([s for s in spans if s["spanType"] == "llm"]), 1)
        self.assertFalse(os.path.exists(os.path.join(self.state_dir, sid + ".json")))

    def test_turn_trace_deterministic_without_state(self):
        """state 丢失（并发覆盖/被删）时 stop 仍与同轮 tool span 同 trace。"""
        sid = "grp-2"
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "T9", "tool_name": "Bash"})
        os.remove(os.path.join(self.state_dir, sid + ".json"))
        self._fire("stop", {"session_id": sid, "turn_id": "T9", "last_assistant_message": "答"})
        spans = self._spans()
        self.assertEqual(len({s["traceId"] for s in spans}), 1)
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["output"], "答")

    def test_two_turns_get_distinct_traces_with_own_prompts(self):
        sid = "grp-3"
        for t, q in [("T1", "第一轮"), ("T2", "第二轮")]:
            self._fire("user_prompt", {"session_id": sid, "turn_id": t, "prompt": q})
            self._fire("stop", {"session_id": sid, "turn_id": t, "last_assistant_message": "答-" + q})
        spans = self._spans()
        roots = [s for s in spans if s["parentSpanId"] is None]
        self.assertEqual(len(roots), 2)
        self.assertEqual(len({r["traceId"] for r in roots}), 2)     # 每轮各一条 trace
        self.assertEqual({r["input"] for r in roots}, {"第一轮", "第二轮"})   # 各轮带各自 prompt

    def test_subagent_tools_join_main_turn_trace(self):
        """子 agent 工具事件带自己的 turn_id：必须挂到主轮次 trace，
        否则会拆出永远没有 llm/root 的孤儿 trace（列表里"没有模型/tokens 的空 trace"）。"""
        sid = "grp-4"
        self._fire("user_prompt", {"session_id": sid, "turn_id": "MAIN", "prompt": "改 Bug"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "SUB-1", "tool_name": "Bash",
                                     "tool_input": "ls", "tool_response": "ok"})
        self._fire("pre_tool_use", {"session_id": sid, "turn_id": "SUB-2", "tool_name": "Bash"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "SUB-2", "tool_name": "Bash",
                                     "tool_input": "pwd", "tool_response": "/"})
        self._fire("stop", {"session_id": sid, "turn_id": "MAIN", "last_assistant_message": "完成"})
        spans = self._spans()
        tids = {s["traceId"] for s in spans}
        self.assertEqual(len(tids), 1, f"子 agent 工具调用应与主轮次同 trace: {tids}")
        self.assertEqual(sorted(s["spanType"] for s in spans), ["agent", "llm", "tool", "tool"])
        llm = next(s for s in spans if s["spanType"] == "llm")
        self.assertEqual(llm["input"], "改 Bug")     # 主轮次 prompt 不因子 agent 事件丢失
        self.assertFalse(os.path.exists(os.path.join(self.state_dir, sid + ".json")))

    def test_eventsWithoutTurnIdStayInOneTrace(self):
        """Claude Code 桥接的事件不带 turn_id：同一条 user_prompt 之后的 tool 与
        stop 的 llm/root 必须留在同一条 trace（否则每个 span 自成一条 trace）。"""
        sid = "grp-6"
        self._fire("user_prompt", {"session_id": sid, "prompt": "claude 风格事件流"})
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "Read",
                                     "tool_input": "a.ts", "tool_response": "ok"})
        self._fire("post_tool_use", {"session_id": sid, "tool_name": "Read",
                                     "tool_input": "b.ts", "tool_response": "ok"})
        self._fire("stop", {"session_id": sid, "last_assistant_message": "答"})
        spans = self._spans()
        tids = {s["traceId"] for s in spans}
        self.assertEqual(len(tids), 1, f"无 turn_id 时 span 应共用一条 trace，实际拆成 {tids}")
        self.assertEqual(sorted(s["spanType"] for s in spans), ["agent", "llm", "tool", "tool"])

    def test_tools_without_prompt_group_by_own_turn(self):
        """无 UserPromptSubmit（中途注册/子 agent 会话）：按事件自身 turn_id 分组。"""
        sid = "grp-5"
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "S1", "tool_name": "Bash"})
        self._fire("pre_tool_use", {"session_id": sid, "turn_id": "S1", "tool_name": "Bash"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "S1", "tool_name": "Bash"})
        self._fire("post_tool_use", {"session_id": sid, "turn_id": "S2", "tool_name": "Bash"})
        spans = self._spans()
        by_turn = {}
        for s in spans:
            by_turn.setdefault(s["traceId"], 0)
            by_turn[s["traceId"]] += 1
        self.assertEqual(sorted(by_turn.values()), [1, 2], by_turn)   # S1×2 / S2×1 各自成 trace
