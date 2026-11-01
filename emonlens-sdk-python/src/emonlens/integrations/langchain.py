"""LangChain 集成：BaseCallbackHandler（对标 Langfuse CallbackHandler）。

用法：
    from emonlens.integrations.langchain import EmonLensCallbackHandler
    handler = EmonLensCallbackHandler(session_id="chat-1")
    chain.invoke(input, config={"callbacks": [handler]})

自动记录：LLM（含 token 用量）、ChatModel、Tool、Retriever、Chain（嵌套关系按
parent_run_id 还原）。未安装 langchain 时亦可独立工作（仅依赖标准回调协议）。
"""
import uuid

from ..client import now_iso
from ..context import get_current, set_current, reset_current
from ..observe import TraceContext

try:
    from langchain_core.callbacks import BaseCallbackHandler as _Base
except Exception:  # langchain 未安装也能用：仅依赖回调协议
    _Base = object


def _short(value):
    from ..transport import serialize
    return serialize(value)


class EmonLensCallbackHandler(_Base):
    """把 LangChain 的 run 事件流映射为 EmonLens span 树。"""

    def __init__(self, session_id=None, user_id=None, environment=None):
        self.session_id = session_id
        self.user_id = user_id
        self.environment = environment
        self._runs = {}            # run_id -> span dict
        self._ctx = None
        self._token = None

    # ---- 上下文 ----
    def _ensure_ctx(self):
        if self._ctx is None:
            self._ctx = get_current() or TraceContext(
                session_id=self.session_id, user_id=self.user_id,
                environment=self.environment, name="langchain_run")
            if get_current() is not self._ctx and get_current() is None:
                self._token = set_current(self._ctx)
        return self._ctx

    def _start(self, run_id, parent_run_id, span_type, name, input_val):
        ctx = self._ensure_ctx()
        span = dict(ctx.base, spanId=uuid.uuid4().hex[:8],
                    parentSpanId=(self._runs[parent_run_id]["spanId"]
                                  if parent_run_id and parent_run_id in self._runs
                                  else None),
                    spanType=span_type, spanName=name,
                    startTime=now_iso(), input=_short(input_val), status="OK")
        self._runs[run_id] = span
        return span

    def _end(self, run_id, output=None, error=None, tokens=None):
        span = self._runs.pop(run_id, None)
        if not span:
            return
        if output is not None:
            span["output"] = _short(output)
        if tokens:
            span["inputTokens"] = tokens.get("prompt_tokens")
            span["outputTokens"] = tokens.get("completion_tokens")
        if error:
            span["status"] = "ERROR"
            span["errorType"] = error
        span["endTime"] = now_iso()
        self._ctx.emit(span)

    # ---- LLM / ChatModel ----
    def on_llm_start(self, serialized, prompts, *, run_id, parent_run_id=None, **kw):
        self._start(run_id, parent_run_id, "llm", "llm_call", prompts)
    on_chat_model_start = on_llm_start

    def on_llm_end(self, response, *, run_id, **kw):
        usage = (getattr(response, "llm_output", None) or {}).get("token_usage")
        generations = getattr(response, "generations", None)
        text = None
        if generations and generations[0]:
            text = getattr(generations[0][0], "text", None)
        self._end(run_id, output=text,
                  tokens={"prompt_tokens": getattr(usage, "prompt_tokens", None),
                          "completion_tokens": getattr(usage, "completion_tokens", None)}
                  if usage else None)

    def on_llm_error(self, error, *, run_id, **kw):
        self._end(run_id, error=type(error).__name__ if not isinstance(error, str) else error)

    # ---- Tool ----
    def on_tool_start(self, serialized, input_str, *, run_id, parent_run_id=None, **kw):
        self._start(run_id, parent_run_id, "tool", kw.get("name") or "tool", input_str)

    def on_tool_end(self, output, *, run_id, **kw):
        self._end(run_id, output=output)

    def on_tool_error(self, error, *, run_id, **kw):
        self._end(run_id, error=type(error).__name__ if not isinstance(error, str) else error)

    # ---- Retriever ----
    def on_retriever_start(self, serialized, query, *, run_id, parent_run_id=None, **kw):
        self._start(run_id, parent_run_id, "retrieval", kw.get("name") or "retriever", query)

    def on_retriever_end(self, documents, *, run_id, **kw):
        self._end(run_id, output=f"{len(documents or [])} docs")

    def on_retriever_error(self, error, *, run_id, **kw):
        self._end(run_id, error=str(error)[:120])

    # ---- Chain ----
    def on_chain_start(self, serialized, inputs, *, run_id, parent_run_id=None, **kw):
        ctx = self._ensure_ctx()
        # 根 chain：无父且当前无 Trace → 作为根 span（agent）
        if parent_run_id is None and get_current() is None and not self._runs:
            self._ctx = TraceContext(session_id=self.session_id,
                                     user_id=self.user_id,
                                     environment=self.environment,
                                     name=kw.get("name") or "chain",
                                     span_type="agent", input=_short(inputs))
            self._token = set_current(self._ctx)
            self._runs[run_id] = self._ctx.root
            return
        self._start(run_id, parent_run_id, "tool", kw.get("name") or "chain", inputs)

    def on_chain_end(self, outputs, *, run_id, **kw):
        if run_id in self._runs and self._runs[run_id] is self._ctx.root:
            root = self._ctx.root
            root["output"] = _short(outputs)
            root["endTime"] = now_iso()
            self._ctx.emit_root()
            self._runs.pop(run_id)
            if self._token is not None:
                reset_current(self._token)
                self._token = None
            return
        self._end(run_id, output=outputs)

    def on_chain_error(self, error, *, run_id, **kw):
        if run_id in self._runs and self._runs[run_id] is self._ctx.root:
            self._ctx.root["status"] = "ERROR"
            self._ctx.root["errorType"] = str(error)[:120]
            self._ctx.root["endTime"] = now_iso()
            self._ctx.emit_root()
            self._runs.pop(run_id)
            return
        self._end(run_id, error=str(error)[:120])
