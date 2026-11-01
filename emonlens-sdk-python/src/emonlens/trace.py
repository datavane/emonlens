"""Trace 上下文管理器：一次 Agent 调用 = 1 个 agent 根 span + N 个子 span。

用法（见 docs/Agent接入教程.md）：
    from emonlens import Trace

    with Trace(session_id="chat-42", input="用户问题") as tr:
        tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
               input_tokens=512, output_tokens=344)
        tr.tool("query_db", input=sql, output=rows)
# 退出时自动补根 span endTime/status 并异步批量上报
"""
import time
import uuid

from . import client as _client
from .transport import serialize

_SPAN_TYPES = ("agent", "llm", "tool", "retrieval")


def _iso(t: float) -> str:
    # 毫秒精度：服务端用 startTime/endTime 重算 durationMs，整秒截断会让毫秒级 span 归零
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t)) + ".%03d" % int((t % 1) * 1000)


class Trace:
    def __init__(self, session_id=None, user_id=None, environment=None,
                 agent_version=None, input=None, trace_id=None, name="invoke_agent",
                 prompt=None, thread_id=None):
        """thread_id: 多轮会话分组标识（LangSmith 命名对齐；session_id 为兼容别名，两者任传其一）。
        以 metadata 形式随 span 存储，不在产品 UI 中作为独立概念露出。
        prompt: client.get_prompt() 返回的 Prompt 对象（或任意带 name/version 属性的对象）；
        传入后根 span attributes 自动携带 prompt_name/prompt_version，建立 trace 关联。"""
        self.trace_id = trace_id or uuid.uuid4().hex[:16]
        session_id = thread_id or session_id
        self._client = _client.get_client()
        self._base = {
            "traceId": self.trace_id,
            "agentVersion": agent_version,
            "sessionId": session_id,
            "userId": user_id,
        }
        cfg = self._client.cfg if self._client else None
        if cfg:
            self._base.setdefault("agentKey", cfg.agent)
            self._base["agentVersion"] = agent_version or cfg.agent_version
            self._base["environment"] = environment or cfg.environment
        else:
            self._base["agentKey"] = "unknown"
            self._base["environment"] = environment or "production"
        self.t0 = time.time()
        prompt_attrs = {}
        if prompt is not None and getattr(prompt, "name", None):
            prompt_attrs = {"attributes": {
                "prompt_name": getattr(prompt, "name", ""),
                "prompt_version": str(getattr(prompt, "version", "") or ""),
            }}
        self.root = dict(self._base, spanId="root", spanType="agent", spanName=name,
                         startTime=_iso(self.t0), input=serialize(input), **prompt_attrs)
        self._spans = [self.root]
        self._ctx_token = None

    # ---- 子 span ----
    def _add(self, span_type: str, name: str, **kw):
        now = time.time()
        # input/output 允许传任意对象（dict/list/对象）：统一序列化为字符串，
        # 否则服务端反序列化失败会整批 400 拒收
        for field in ("input", "output"):
            if field in kw and kw[field] is not None:
                kw[field] = serialize(kw[field])
        span = dict(self._base, spanId=uuid.uuid4().hex[:8], parentSpanId="root",
                    spanType=span_type, spanName=name,
                    startTime=_iso(kw.pop("_start", now)), endTime=_iso(kw.pop("_end", now)),
                    **kw)
        if span.get("status") is None:
            span["status"] = "OK"
        self._spans.append(span)
        return span

    def llm(self, name="call_model", model=None, input=None, output=None,
            input_tokens=0, output_tokens=0, cached_input_tokens=0, error=None, cost_usd=None):
        kw = dict(modelName=model, input=input, output=output,
                  inputTokens=input_tokens, outputTokens=output_tokens,
                  cachedInputTokens=cached_input_tokens,
                  status="ERROR" if error else "OK")
        if error:
            kw["errorType"] = str(error)
        if cost_usd is not None:
            kw["costUsd"] = cost_usd
        return self._add("llm", name, **kw)

    def tool(self, name, tool_name=None, input=None, output=None, error=None):
        kw = dict(toolName=tool_name or name, input=input, output=output,
                  status="ERROR" if error else "OK")
        if error:
            kw["errorType"] = str(error)
        return self._add("tool", name, **kw)

    def retrieval(self, name, input=None, output=None):
        return self._add("retrieval", name, input=input, output=output)

    def event(self, name, attributes=None):
        """瞬时事件：0ms 子 span，attributes 任意结构。"""
        return self._add("agent", name, input=None, output=None, attributes=attributes)

    # ---- 上下文 ----
    def __enter__(self):
        # 桥接：注册到 contextvars，使嵌套的 @observe 函数挂到本 Trace（而非自建孤儿 trace）
        try:
            from .observe import TraceContext
            from .context import set_current
            if self._client and self._client.cfg.configured:
                self._ctx = TraceContext.adopt(self.root, self._client)
                self._ctx_token = set_current(self._ctx)
        except Exception:
            self._ctx_token = None      # 观测绝不影响业务
        return self

    def __exit__(self, exc_type, exc, tb):
        self.root["endTime"] = _iso(time.time())
        if exc_type is not None:
            self.root["status"] = "ERROR"
            self.root["errorType"] = exc_type.__name__
        elif self.root.get("status") is None:
            self.root["status"] = "OK"
        if self._client:
            for span in self._spans:
                self._client.emit(span)
        if self._ctx_token is not None:
            from .context import reset_current
            try:
                reset_current(self._ctx_token)
            except Exception:
                pass
            self._ctx_token = None
        return False  # 不吞异常
