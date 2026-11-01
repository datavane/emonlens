package io.emonlens.server.engine.event;

import io.emonlens.server.storage.entity.EventEntity;

/**
 * 事件处理器扩展点（engine 模块内）。
 * 后续 Evaluation/Webhook 回推等事件类型各自实现本接口注册为 Spring bean 即可。
 */
public interface EventHandler {

    boolean supports(String eventType);

    void handle(EventEntity event);
}
