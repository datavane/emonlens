"""全局 Client：后台队列 + 批量线程 + flush。

- emit() 把 span 放入队列，永不阻塞业务
- 守护线程按 FLUSH_INTERVAL(2s) 或 MAX_BATCH(100) 批量上报
- flush(timeout) 同步排空队列（atexit 注册）
- 未配置（no-op）时 emit 静默丢弃
"""
# PEP 604 联合类型（X | None）在模块级/函数注解中求值；包声明 requires-python>=3.9，
# 必须延迟求值，否则 3.9 下 import 即 TypeError
from __future__ import annotations

import atexit
import queue
import threading
import sys
import time

from .config import Config
from .transport import post_spans, fallback_to_disk


class Client:
    def __init__(self, config: Config):
        self.cfg = config
        self._queue = queue.Queue(maxsize=max(int(getattr(config, "max_queue", 10000) or 10000), 1))
        self._wake = threading.Event()
        self._close = threading.Event()
        self._lock = threading.Lock()
        self._stats_lock = threading.Lock()
        self._flush_done = threading.Event()
        self._dropped = 0
        self._atexit = None
        if config.configured:
            self._thread = threading.Thread(target=self._run, daemon=True,
                                            name="emonlens-reporter")
            self._thread.start()
            self._atexit = atexit.register(self.flush, 5)
        else:
            self._thread = None

    # ---- 公开 ----
    def emit(self, span: dict) -> None:
        if not self.cfg.configured:
            return
        if self.cfg.redact:
            try:
                redacted = self.cfg.redact(span)
            except Exception:
                # 脱敏失败时宁可丢弃，也不能把未脱敏内容发出去；异常不能冒泡到业务
                self._count_drop(1)
                return
            if redacted is not None:
                span = redacted
        try:
            self._queue.put_nowait(span)
            self._wake.set()
        except queue.Full:
            self._count_drop(1)  # 队列打满：计数 + 本地兜底落盘（可恢复），保业务
            try:
                fallback_to_disk(self.cfg, [span])
            except Exception:
                pass

    def _count_drop(self, count: int) -> None:
        with self._stats_lock:
            self._dropped += count

    def flush(self, timeout: float = 5.0) -> None:
        """同步排空：唤醒 worker 把队列发完。"""
        if not self.cfg.configured:
            return
        self._flush_done.clear()
        self._wake.set()
        self._flush_done.wait(timeout)

    def shutdown(self, timeout: float = 5.0) -> None:
        self.flush(timeout)
        self._close.set()
        self._wake.set()
        if self._thread:
            self._thread.join(timeout)
        # 反注册 atexit：被 init() 替换掉的旧 client 若留着 handler，
        # 进程退出时每个都会挂满 5s（线程已死，flush 的 Event 等待必然超时）
        if self._atexit is not None:
            try:
                atexit.unregister(self._atexit)
            except Exception:
                pass
            self._atexit = None

    # ---- 内部 ----
    def _run(self):
        while not self._close.is_set():
            try:
                self._wake.wait(timeout=self.cfg.flush_interval)
                self._wake.clear()
                self._drain()
            except Exception:
                # 上报线程必须活着：任何意外都不能让后续 span 永远堆在队列里
                time.sleep(min(max(float(self.cfg.flush_interval or 1), 0.1), 30))
            if self._close.is_set():
                break
            # flush() 同步语义：队列已空时通知调用方
            if self._queue.empty():
                self._flush_done.set()
        try:
            self._drain()
        except Exception:
            pass
        self._flush_done.set()

    def _drain(self):
        while True:
            batch = []
            try:
                batch.append(self._queue.get_nowait())
            except queue.Empty:
                return
            # 取满一批（服务端单批上限 500，超出会被 400 拒收）
            batch_limit = min(max(int(self.cfg.max_batch or 100), 1), 500)
            while len(batch) < batch_limit:
                try:
                    batch.append(self._queue.get_nowait())
                except queue.Empty:
                    break
            # 脱敏已在 emit() 完成；这里不再调用 hook，避免重复调用与线程被异常杀死
            try:
                post_spans(self.cfg, batch)
            except Exception:
                try:
                    fallback_to_disk(self.cfg, batch)
                except Exception:
                    pass

    @property
    def dropped(self) -> int:
        return self._dropped


# ---- Prompt 运行时拉取（Langfuse get_prompt 对标） ----
class Prompt:
    """服务端提示词的运行时视图：prompt 文本 / version / config，compile() 渲染变量。"""

    def __init__(self, name: str, version: int, prompt: str, config=None, labels=None):
        self.name = name
        self.version = version
        self.prompt = prompt
        self.config = config or {}
        self.labels = labels or []

    def compile(self, **variables) -> str:
        """渲染 {{变量}}；未提供的变量保留原样（与服务端语义一致）。"""
        import re
        def _sub(m):
            key = m.group(1)
            return str(variables[key]) if key in variables else m.group(0)
        return re.sub(r"\{\{\s*([a-zA-Z0-9_]+)\s*}}", _sub, self.prompt)

    def __repr__(self):
        return f"<Prompt {self.name} v{self.version}>"


class _PromptCache:
    """线程安全 TTL 缓存：按 (name, label) 记忆 Prompt，命中未过期直接返回。"""

    def __init__(self, ttl: float = 60.0):
        self.ttl = ttl
        self._store = {}
        self._lock = threading.Lock()

    def get(self, key):
        with self._lock:
            hit = self._store.get(key)
            if hit and time.time() - hit[0] < self.ttl:
                return hit[1]
        return None

    def put(self, key, prompt):
        with self._lock:
            self._store[key] = (time.time(), prompt)


def get_prompt(name: str, label: str = "production", cache_ttl: float = 60.0,
               force_refresh: bool = False) -> "Prompt | None":
    """拉取提示词（带 TTL 缓存）；未初始化/网络失败时返回 None，不阻塞业务。

    用法：
        p = emonlens.get_prompt("support-assistant")           # production 版本
        text = p.compile(question="工单 #1024 的处理进度是多少？")
        with Trace(prompt=p) as tr:                            # trace 自动关联 prompt 版本
            tr.llm(input=text, output=answer)
    """
    c = get_client()
    if not c or not c.cfg.configured:
        return None
    key = (name, label)
    cache = getattr(c, "_prompt_cache", None)
    if cache is None:
        with c._lock if hasattr(c, "_lock") else threading.Lock():
            cache = getattr(c, "_prompt_cache", None)
            if cache is None:
                cache = _PromptCache(cache_ttl)
                c._prompt_cache = cache
    if not force_refresh:
        hit = cache.get(key)
        if hit is not None:
            return hit
    try:
        from .transport import get_json
        data = get_json(c.cfg, f"/api/v1/ingest/prompts/{name}/resolve?label={label}")
        if not data:
            return None
        p = Prompt(name, data.get("version") or 0, data.get("prompt") or "",
                   config=data.get("config"), labels=data.get("labels"))
    except Exception as e:
        if c.cfg.debug:
            print("[emonlens] get_prompt failed:", e, file=sys.stderr)
        return None
    cache.put(key, p)
    return p


# ---- 全局单例管理 ----
_global: Client | None = None
_swap_lock = threading.Lock()


def get_client() -> Client | None:
    """全局客户端。未显式 init() 时按环境变量惰性自动初始化：
    只需设置 EMONLENS_HOST/EMONLENS_INGEST_KEY/EMONLENS_AGENT 三个环境变量即可接入
    （README 快速开始语义）；环境变量不齐时返回 no-op 客户端（emit 静默、无上报线程）。"""
    global _global
    if _global is None:
        with _swap_lock:
            if _global is None:
                _global = Client(Config.from_env())
    return _global


def set_client(client: Client | None) -> None:
    global _global
    with _swap_lock:
        old, _global = _global, client
    if old:
        old.shutdown()


def now_iso() -> str:
    # 毫秒精度：服务端按 startTime/endTime 重算 durationMs，整秒会丢亚秒信息
    return iso_from(time.time())


def iso_from(t: float) -> str:
    """把 epoch 秒（可含小数）渲染成带毫秒的本地 ISO 时间。

    事后补记型埋点（OpenAI 集成在调用返回后才写 span）必须用它还原真实的开始时刻，
    否则 startTime 会被写成调用后的时刻，span 耗时恒为 0。
    """
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t)) + ".%03d" % int((t % 1) * 1000)


# ---- 模块级 API（__init__ 转发） ----
def init(host=None, ingest_key=None, agent=None, agent_version=None,
         environment=None, flush_interval=None, max_batch=None,
         debug=None, redact=None) -> None:
    """初始化全局客户端（覆盖环境变量）。未配置完整时进入 no-op 模式。"""
    import os
    cfg = Config.from_env()
    cfg.apply_kwargs(host=host, ingest_key=ingest_key, agent=agent,
                     agent_version=agent_version, environment=environment,
                     flush_interval=flush_interval, max_batch=max_batch,
                     debug=debug, redact=redact)
    # host/key 给了但缺 agent 时：不报错、静默 no-op——调用方很难自查，
    # 这里给一条明确告警（观测 SDK 的首要故障模式就是"配了但没数据"）
    if not cfg.configured and cfg.host and cfg.ingest_key and not cfg.agent:
        print("[emonlens] init incomplete: agent missing → all spans are dropped. "
              "pass agent=<agentKey> or set EMONLENS_AGENT.", file=sys.stderr)
    set_client(Client(cfg))


def flush(timeout: float = 5.0) -> None:
    """同步排空上报队列（脚本/CLI 退出前调用）。"""
    c = get_client()
    if c:
        c.flush(timeout)
