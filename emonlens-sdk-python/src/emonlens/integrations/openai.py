"""OpenAI drop-in 集成（对标 Langfuse 的 openai 集成）。

用法一（替换 import）：
    from emonlens.integrations.openai import OpenAI
    client = OpenAI()                    # 其余用法完全不变
    resp = client.chat.completions.create(...)

用法二（包装已有实例）：
    from openai import OpenAI as _OAI
    from emonlens.integrations.openai import wrap_openai
    client = wrap_openai(_OAI())

自动记录 llm span：model、usage tokens、messages→input、回复→output、耗时、ERROR。
当前有 @observe Trace 时作为其子 span；否则自动创建独立 Trace。
"""
import time

from ..client import iso_from, now_iso
from ..context import get_current, set_current, reset_current, set_span_frame, reset_span
from ..observe import TraceContext
from ..transport import serialize


def _record(kwargs, response, error, start, span_type="llm", name="openai.chat"):
    c_client = _get_client()
    if not c_client:
        return
    model = kwargs.get("model")
    usage = getattr(response, "usage", None) if response is not None else None
    output = None
    if response is not None:
        choices = getattr(response, "choices", None)
        if choices:
            msg = getattr(choices[0], "message", None)
            output = getattr(msg, "content", None)
    model = getattr(response, "model", None) or model

    ctx = get_current()
    own = ctx is None
    # startTime 用调用前捕获的时刻：本函数在响应返回后才执行，
    # 若用 now_iso() 会把开始时刻写成调用后，span 耗时恒为 0
    start_iso = iso_from(start)
    if own:
        ctx = TraceContext(name=name, span_type="agent", input=None)
        input_val = serialize(kwargs.get("messages"))
        span = dict(ctx.base, spanId="root", parentSpanId=None,
                    spanType=span_type, spanName=name,
                    startTime=start_iso, input=input_val, status="OK")
    else:
        span = dict(ctx.base, spanId=ctx.new_span_id(),
                    parentSpanId=ctx.parent_span_id,
                    spanType=span_type, spanName=name,
                    startTime=start_iso, input=serialize(kwargs.get("messages")),
                    status="OK")
    token_trace = set_current(ctx) if own else None
    token_span = set_span_frame(span)
    if model:
        span["modelName"] = model
    span["output"] = output
    if usage is not None:
        span["inputTokens"] = getattr(usage, "prompt_tokens", 0) or 0
        span["outputTokens"] = getattr(usage, "completion_tokens", 0) or 0
        # 提示缓存命中量：OpenAI usage.prompt_tokens_details.cached_tokens；
        # 兼容 DeepSeek 等兼容端点的 prompt_cache_hit_tokens / cached_tokens 直挂字段
        cached = None
        details = getattr(usage, "prompt_tokens_details", None)
        if details is not None:
            cached = getattr(details, "cached_tokens", None)
        if cached is None:
            cached = getattr(usage, "prompt_cache_hit_tokens", None)
        if cached is None:
            cached = getattr(usage, "cached_tokens", None)
        if cached:
            span["cachedInputTokens"] = int(cached)
    if error is not None:
        span["status"] = "ERROR"
        span["errorType"] = type(error).__name__
    span["endTime"] = now_iso()
    reset_span(token_span)
    if own:
        reset_current(token_trace)
    ctx.emit(span)


def _get_client():
    from .. import client as _c
    return _c.get_client()


class _Completions:
    def __init__(self, inner):
        self._inner = inner

    def create(self, *args, **kwargs):
        start = time.time()
        try:
            response = self._inner.create(*args, **kwargs)
        except Exception as e:
            _record(kwargs, None, e, start)
            raise
        _record(kwargs, response, None, start)
        return response


class _Chat:
    def __init__(self, inner):
        self._inner = inner
        self.completions = _Completions(inner.completions)

    def __getattr__(self, item):
        return getattr(self._inner, item)


class _AsyncCompletions:
    def __init__(self, inner):
        self._inner = inner

    async def create(self, *args, **kwargs):
        start = time.time()
        try:
            response = await self._inner.create(*args, **kwargs)
        except Exception as e:
            _record(kwargs, None, e, start)
            raise
        _record(kwargs, response, None, start)
        return response


class _AsyncChat:
    def __init__(self, inner):
        self._inner = inner
        self.completions = _AsyncCompletions(inner.completions)

    def __getattr__(self, item):
        return getattr(self._inner, item)


def wrap_openai(client):
    """包装已有 OpenAI / AsyncOpenAI 实例（就地替换 chat 通道）。"""
    is_async = type(client).__module__.startswith("openai") and "Async" in type(client).__name__
    client.chat = _AsyncChat(client.chat) if is_async else _Chat(client.chat)
    return client


class OpenAI:
    """drop-in 替代 openai.OpenAI。"""

    def __init__(self, *args, **kwargs):
        from openai import OpenAI as _OpenAI
        self._client = _OpenAI(*args, **kwargs)
        self.chat = _Chat(self._client.chat)

    def __getattr__(self, item):
        return getattr(self._client, item)


class AsyncOpenAI:
    """drop-in 替代 openai.AsyncOpenAI。"""

    def __init__(self, *args, **kwargs):
        from openai import AsyncOpenAI as _AsyncOpenAI
        self._client = _AsyncOpenAI(*args, **kwargs)
        self.chat = _AsyncChat(self._client.chat)

    def __getattr__(self, item):
        return getattr(self._client, item)
