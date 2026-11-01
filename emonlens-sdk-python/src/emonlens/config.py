"""EmonLens SDK 配置：环境变量优先，init() 可覆盖。

环境变量（对齐 LangSmith 的 env 开关体验）：
    EMONLENS_HOST             服务地址，如 http://emonlens:8300
    EMONLENS_INGEST_KEY       Ingest Key（tk-...），数据面认证
    EMONLENS_AGENT            agentKey，自动填入每个 span
    EMONLENS_AGENT_VERSION    默认 v1
    EMONLENS_ENVIRONMENT      默认 production
    EMONLENS_FLUSH_INTERVAL   批量发送间隔秒，默认 2
    EMONLENS_MAX_BATCH        单批 span 数，默认 100（服务端上限 500，超出自动夹紧）
    EMONLENS_MAX_QUEUE        内存队列上限，默认 10000（打满后单条落盘兜底）
    EMONLENS_DEBUG            true 打印上报日志

数值型环境变量解析失败时回退默认值：SDK 不能因为一个拼写错误让宿主应用崩溃。
"""
import os
from dataclasses import dataclass, field


def _env_float(name: str, default: float) -> float:
    try:
        value = float(os.environ.get(name) or default)
        return value if value > 0 else default
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(os.environ.get(name) or default)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, value))


@dataclass
class Config:
    host: str = ""
    ingest_key: str = ""
    agent: str = ""
    agent_version: str = "v1"
    environment: str = "production"
    flush_interval: float = 2.0
    max_batch: int = 100
    max_queue: int = 10000
    debug: bool = False
    # 自定义脱敏钩子：fn(span_dict) -> span_dict
    redact: object = None
    _initialized: bool = field(default=False, repr=False)

    @property
    def configured(self) -> bool:
        return bool(self._initialized and self.host and self.ingest_key and self.agent)

    @property
    def events_url(self) -> str:
        return self.host.rstrip("/") + "/api/v1/ingest/events"

    def apply_kwargs(self, **kw) -> None:
        for key, value in kw.items():
            if value is not None and hasattr(self, key):
                setattr(self, key, value)
        self._initialized = True

    @classmethod
    def from_env(cls) -> "Config":
        cfg = cls()
        cfg.host = os.environ.get("EMONLENS_HOST", "")
        cfg.ingest_key = os.environ.get("EMONLENS_INGEST_KEY", "")
        cfg.agent = os.environ.get("EMONLENS_AGENT", "")
        cfg.agent_version = os.environ.get("EMONLENS_AGENT_VERSION", "v1")
        cfg.environment = os.environ.get("EMONLENS_ENVIRONMENT", "production")
        cfg.flush_interval = _env_float("EMONLENS_FLUSH_INTERVAL", 2.0)
        cfg.max_batch = _env_int("EMONLENS_MAX_BATCH", 100, 1, 500)
        cfg.max_queue = _env_int("EMONLENS_MAX_QUEUE", 10000, 100, 1_000_000)
        cfg.debug = os.environ.get("EMONLENS_DEBUG", "").lower() in ("1", "true", "yes")
        cfg._initialized = bool(cfg.host and cfg.ingest_key and cfg.agent)
        return cfg
