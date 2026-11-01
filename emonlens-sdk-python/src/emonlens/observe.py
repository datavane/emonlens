"""@observe 全自动埋点（对标 Langfuse @observe / LangSmith @traceable）。

- 最外层被装饰函数调用 = 一个新 Trace（根 span）
- 嵌套被装饰函数 = 当前 Trace 下的子 span（contextvars 自动挂树）
- 入参/返回值自动序列化为 input/output；异常 → ERROR + errorType 并重抛
- 支持 sync 与 async；未初始化时 no-op
- emonlens.update_current(model=..., input_tokens=..., output_tokens=...) 补充当前 span
- emonlens.propagate(fn) 把上下文复制到新线程
"""
import functools
import inspect
import uuid

from . import client as _client
from .context import (get_current, get_span_frame, reset_current, reset_span,
                      set_current, set_span_frame)
from .client import now_iso
from .transport import serialize


class TraceContext:
    """一次根调用产生的追踪上下文。"""

    def __init__(self, session_id=None, user_id=None, environment=None,
                 name="invoke_agent", span_type="agent", input=None):
        c = _client.get_client()
        cfg = c.cfg if c else None
        self.trace_id = uuid.uuid4().hex[:16]
        self._client = c
        base = {"traceId": self.trace_id}
        if cfg:
            base["agentKey"] = cfg.agent
            base["agentVersion"] = cfg.agent_version
            base["environment"] = environment or cfg.environment
        else:
            base["agentKey"] = "unknown"
            base["environment"] = environment or "production"
        self.session_id = session_id
        self.user_id = user_id
        self.base = base
        self.root = dict(base, spanId="root", parentSpanId=None,
                         spanType=span_type, spanName=name,
                         startTime=now_iso(), input=input,
                         status="OK", sessionId=session_id, userId=user_id)
        self._root_emitted = False
        self._stack = []          # 未完成 span 栈（spanId）

    @classmethod
    def adopt(cls, root: dict, client, session_id=None, user_id=None):
        """以既有根 span（trace.Trace 手动上下文）为底建立上下文：
        使 @observe 嵌套在 `with Trace(...)` 内时挂到同一 trace，而不是自建孤儿 trace。
        root 由 Trace.__exit__ 负责 emit，这里只提供挂树上下文。"""
        c = cls.__new__(cls)
        c.trace_id = root.get("traceId") or ""
        c._client = client
        c.base = {k: root[k] for k in ("traceId", "agentKey", "agentVersion", "environment")
                  if k in root}
        c.session_id = root.get("sessionId")
        c.user_id = root.get("userId")
        c.root = root
        c._root_emitted = True       # root 由 Trace.__exit__ 统一 emit
        c._stack = []
        return c

    @property
    def parent_span_id(self):
        # 栈空时挂到根 span（trace_context 场景根已存在但未入栈）
        return self._stack[-1] if self._stack else "root"

    def new_span_id(self) -> str:
        return uuid.uuid4().hex[:8]

    def push(self, span_id: str):
        self._stack.append(span_id)

    def pop(self, span_id: str):
        while self._stack:
            if self._stack.pop() == span_id:
                break

    def emit(self, span: dict):
        if self._client:
            self._client.emit(span)

    def emit_root(self):
        if not self._root_emitted:
            self._root_emitted = True
            self.emit(self.root)


def update_current(model=None, input_tokens=None, output_tokens=None,
                   cost_usd=None, attributes=None):
    """补充当前（栈顶）span 的模型/用量/属性信息。"""
    frame = get_span_frame()
    if not frame:
        return
    if model:
        frame["modelName"] = model
    if input_tokens is not None:
        frame["inputTokens"] = input_tokens
    if output_tokens is not None:
        frame["outputTokens"] = output_tokens
    if cost_usd is not None:
        frame["costUsd"] = cost_usd
    if attributes:
        attrs = frame.setdefault("attributes", {})
        try:
            attrs.update(attributes)
        except Exception:
            pass


def set_session(session_id=None, user_id=None, environment=None):
    """为当前 Trace 设置会话/用户/环境（影响尚未结束的根 span 与后续子 span）。"""
    ctx = get_current()
    if not ctx:
        return
    if session_id is not None:
        ctx.session_id = session_id
        ctx.root["sessionId"] = session_id
    if user_id is not None:
        ctx.user_id = user_id
        ctx.root["userId"] = user_id
    if environment is not None:
        ctx.root["environment"] = environment


def current_trace_id() -> str:
    ctx = get_current()
    return ctx.trace_id if ctx else ""


def _capture_input(args, kwargs, capture):
    if not capture:
        return None
    payload = {"args": list(args)}
    payload.update(kwargs)
    return serialize(payload)


def _start_span(ctx, fname, span_type, input_val, session_id, user_id, environment):
    own_trace = ctx is None
    if own_trace:
        ctx = TraceContext(session_id=session_id, user_id=user_id,
                           environment=environment, name=fname,
                           span_type=span_type or "agent", input=input_val)
    span = dict(ctx.base,
                spanId=ctx.new_span_id(),
                parentSpanId=None if own_trace else ctx.parent_span_id,
                spanType=span_type or ("agent" if own_trace else "tool"),
                spanName=fname,
                startTime=now_iso(),
                input=input_val,
                status="OK",
                sessionId=ctx.session_id,
                userId=ctx.user_id)
    ctx.push(span["spanId"])
    return ctx, span, own_trace


def _finish_span(ctx, span, token_trace, token_span, result=None, error=None,
                 capture_output=True):
    if error is not None:
        span["status"] = "ERROR"
        span["errorType"] = error
    else:
        span["output"] = serialize(result) if capture_output else None
    span["endTime"] = now_iso()
    ctx.pop(span["spanId"])
    if token_trace is not None:
        reset_current(token_trace)
    reset_span(token_span)
    ctx.emit(span)


def observe(name=None, span_type=None, session_id=None, user_id=None,
            environment=None, capture_input=True, capture_output=True):
    """全自动埋点装饰器。

    @observe()                       # 根：函数名即 span 名，默认 agent 类型
    @observe(span_type="llm")        # 子：LLM 调用（配合 update_current 补 tokens）
    @observe(span_type="tool")       # 子：工具调用
    """
    def decorator(fn):
        fname = name or getattr(fn, "__name__", "span")

        if inspect.iscoroutinefunction(fn):
            @functools.wraps(fn)
            async def async_wrapper(*args, **kwargs):
                if not _client.get_client():
                    return await fn(*args, **kwargs)
                input_val = _capture_input(args, kwargs, capture_input)
                ctx, span, own_trace = _start_span(
                    get_current(), fname, span_type, input_val,
                    session_id, user_id, environment)
                token_trace = set_current(ctx) if own_trace else None
                token_span = set_span_frame(span)
                try:
                    result = await fn(*args, **kwargs)
                except Exception as e:
                    _finish_span(ctx, span, token_trace, token_span,
                                 error=type(e).__name__, capture_output=False)
                    raise
                _finish_span(ctx, span, token_trace, token_span,
                             result=result, capture_output=capture_output)
                return result
            return async_wrapper

        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            if not _client.get_client():
                return fn(*args, **kwargs)
            input_val = _capture_input(args, kwargs, capture_input)
            ctx, span, own_trace = _start_span(
                get_current(), fname, span_type, input_val,
                session_id, user_id, environment)
            token_trace = set_current(ctx) if own_trace else None
            token_span = set_span_frame(span)
            try:
                result = fn(*args, **kwargs)
            except Exception as e:
                _finish_span(ctx, span, token_trace, token_span,
                             error=type(e).__name__, capture_output=False)
                raise
            _finish_span(ctx, span, token_trace, token_span,
                         result=result, capture_output=capture_output)
            return result
        return wrapper

    # 支持裸用法 @observe（直接装饰函数）
    if callable(name):
        fn, name = name, None
        return decorator(fn)
    return decorator


def trace_context(session_id=None, user_id=None, environment=None, name="session"):
    """显式 Trace 上下文管理器（把任意代码块归为一个 Trace）：

        with emonlens.trace_context(session_id="c1"):
            @observe()
            def step(): ...
    """
    return _CtxManager(session_id, user_id, environment, name)


class _CtxManager:
    def __init__(self, session_id, user_id, environment, name):
        self._kw = dict(session_id=session_id, user_id=user_id,
                        environment=environment, name=name)
        self._token = None

    def __enter__(self):
        ctx = TraceContext(input=None, **{
            k: v for k, v in self._kw.items() if k != "name"})
        ctx.root["spanName"] = self._kw["name"]
        self._token = set_current(ctx)
        return ctx

    def __exit__(self, exc_type, exc, tb):
        ctx = get_current()
        if ctx and self._token is not None:
            if exc_type is not None:
                ctx.root["status"] = "ERROR"
                ctx.root["errorType"] = exc_type.__name__
            ctx.root["endTime"] = now_iso()
            ctx.emit_root()
            reset_current(self._token)
        return False


def propagate(fn):
    """把当前 Trace 上下文复制到新线程执行。"""
    from .context import propagate as _propagate
    return _propagate(fn)
