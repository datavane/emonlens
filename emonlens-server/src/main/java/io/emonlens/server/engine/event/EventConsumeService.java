package io.emonlens.server.engine.event;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.EventHandlerDeliveryEntity;
import io.emonlens.server.storage.mapper.EventHandlerDeliveryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件消费编排：认领 -> 分发到支持的 EventHandler -> DONE/重试。
 * 调用方（worker 线程或独立 worker 进程）周期性调用 processPendingBatch。
 * 另周期性回收超时未完成的 CLAIMED 事件（实例崩溃后的租约恢复）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventConsumeService {

    private final EventService eventService;
    private final List<EventHandler> handlers;
    private final EventHandlerDeliveryMapper deliveryMapper;

    /** 租约回收最小间隔：事件循环空轮 1s，不能每轮都打全表条件 UPDATE */
    static final long RECLAIM_INTERVAL_MS = 60_000;
    private volatile long lastReclaimAt = 0L;

    /** @return 本轮成功处理的事件数 */
    public int processPendingBatch(int max) {
        reclaimExpiredPeriodically();
        List<EventEntity> claimed = eventService.claimBatch(max);
        int processed = 0;
        for (EventEntity event : claimed) {
            try {
                dispatch(event);
                eventService.complete(event.getId());
                processed++;
            } catch (Exception e) {
                log.error("event handling failed, id={}, type={}, retryCount={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e);
                eventService.fail(event);
            }
        }
        return processed;
    }

    private void reclaimExpiredPeriodically() {
        long now = System.currentTimeMillis();
        if (now - lastReclaimAt < RECLAIM_INTERVAL_MS) {
            return;
        }
        lastReclaimAt = now;
        try {
            eventService.reclaimExpiredLeases();
        } catch (Exception e) {
            log.error("event lease reclaim failed", e);
        }
    }

    /** fan-out：同类型事件的所有 handler 都执行（如 SCORE_CHANGED 同时触发重算与回推）。
     *  handler 级账本保证重试只重跑失败 handler，成功后不会重复发 webhook / 重复重算评分。 */
    private void dispatch(EventEntity event) {
        int matched = 0;
        for (EventHandler handler : handlers) {
            if (!handler.supports(event.getEventType())) {
                continue;
            }
            matched++;
            String handlerKey = handler.getClass().getName();
            if (alreadyHandled(event.getId(), handlerKey)) {
                continue;
            }
            handler.handle(event);
            markHandled(event.getId(), handlerKey);
        }
        if (matched == 0) {
            log.warn("no handler for event type {}, mark done, id={}", event.getEventType(), event.getId());
        }
    }

    private boolean alreadyHandled(String eventId, String handlerKey) {
        Long count = deliveryMapper.selectCount(Wrappers.<EventHandlerDeliveryEntity>lambdaQuery()
                .eq(EventHandlerDeliveryEntity::getEventId, eventId)
                .eq(EventHandlerDeliveryEntity::getHandlerKey, handlerKey));
        return count != null && count > 0;
    }

    private void markHandled(String eventId, String handlerKey) {
        EventHandlerDeliveryEntity delivery = new EventHandlerDeliveryEntity();
        delivery.setEventId(eventId);
        delivery.setHandlerKey(handlerKey);
        delivery.setStatus("DONE");
        delivery.setCreatedAt(LocalDateTime.now());
        delivery.setUpdatedAt(delivery.getCreatedAt());
        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException e) {
            // 并发重试：另一实例已完成该 handler
        }
    }
}
