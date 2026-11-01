package io.emonlens.server.storage.span;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * SpanEventStore 注册器：收集全部实现 Bean，按 emonlens.storage.mode 装配激活实现。
 * 目标实现不可用时（如 starrocks 未配 JDBC）启动即失败，避免静默写错库。
 */
@Slf4j
@Component
public class SpanEventStoreRegistry {

    private final Map<String, SpanEventStore> byKind = new HashMap<>();
    private final SpanEventStore active;

    public SpanEventStoreRegistry(List<SpanEventStore> candidates, EmonLensProperties properties) {
        for (SpanEventStore store : candidates) {
            byKind.put(store.kind(), store);
        }
        String configuredMode = properties.getStorage().getMode();
        SpanEventStore selected = byKind.get(configuredMode == null ? "" : configuredMode.trim().toLowerCase());
        if (selected == null) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "span store mode '" + configuredMode + "' 不可用，当前已注册实现: " + byKind.keySet()
                            + "（starrocks 需配置 emonlens.storage.starrocks.jdbc-url）");
        }
        this.active = selected;
        log.info("span event store active: kind={}, registered={}", active.kind(), byKind.keySet());
    }

    /** 当前激活的存储实现（由 emonlens.storage.mode 决定） */
    public SpanEventStore active() {
        return active;
    }

    /** 按类型获取实现（管理端点/诊断用） */
    public SpanEventStore byKind(String kind) {
        SpanEventStore store = byKind.get(kind);
        if (store == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "span store kind not registered: " + kind);
        }
        return store;
    }
}
