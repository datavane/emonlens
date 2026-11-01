"""Trace 上下文（contextvars）：@observe 嵌套调用自动挂树的关键。

- 线程安全：每个线程独立上下文
- asyncio 安全：ContextVar 在 task 间自动隔离
- 跨线程：使用 propagate_context() 显式传播
"""
import contextvars

_current_trace = contextvars.ContextVar("emonlens_trace_ctx", default=None)
_current_span = contextvars.ContextVar("emonlens_span_frame", default=None)


def get_current():
    """当前 TraceContext（无则 None）。"""
    return _current_trace.get()


def set_current(ctx):
    """设置当前上下文，返回 token 供 reset。"""
    return _current_trace.set(ctx)


def reset_current(token):
    _current_trace.reset(token)


def set_span_frame(frame):
    """栈顶 span dict（update_current 的作用目标）。"""
    return _current_span.set(frame)


def reset_span(token):
    _current_span.reset(token)


def get_span_frame():
    return _current_span.get()


def current_trace_id() -> str:
    ctx = _current_trace.get()
    return ctx.trace_id if ctx else ""


def current_parent_span_id() -> str:
    """当前栈顶 spanId（新 span 的 parent）；无 Trace 时为空。"""
    ctx = _current_trace.get()
    if not ctx:
        return ""
    frame = _current_span.get()
    return frame["spanId"] if frame else "root"


def propagate(fn):
    """把当前 Trace 上下文复制到新线程执行：

        t = threading.Thread(target=emonlens.propagate(worker), args=(...))
    """
    context = contextvars.copy_context()

    def wrapper(*args, **kwargs):
        return context.run(fn, *args, **kwargs)

    wrapper.__wrapped__ = fn
    wrapper.__name__ = getattr(fn, "__name__", "propagated")
    return wrapper
