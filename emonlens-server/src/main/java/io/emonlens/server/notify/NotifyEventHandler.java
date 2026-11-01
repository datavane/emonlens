package io.emonlens.server.notify;

import org.springframework.stereotype.Component;

import io.emonlens.server.engine.event.EventHandler;
import io.emonlens.server.storage.entity.EventEntity;

import lombok.RequiredArgsConstructor;

/**
 * 通知事件 handler：只处理 INCIDENT_* 事件（incident 是正确的通知粒度——
 * 按 fingerprint 聚合后的 finding 远少于每-trace 通知，且 Incident 有生命周期可管理）。
 * FINDING_CREATED 不走通知（防风暴）；发送失败外抛触发事件退避重试
 * （1m/2m/3m 后重投失败渠道，SUCCESS 渠道幂等跳过；超限事件置 DEAD 可排查）。
 */
@Component
@RequiredArgsConstructor
public class NotifyEventHandler implements EventHandler {

    private final NotifyDispatchService notifyDispatchService;

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("INCIDENT_");
    }

    @Override
    public void handle(EventEntity event) {
        notifyDispatchService.dispatch(event);
    }
}
