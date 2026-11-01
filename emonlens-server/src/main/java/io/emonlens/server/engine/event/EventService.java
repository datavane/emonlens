package io.emonlens.server.engine.event;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.mapper.EventMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部事件（et_event）：无中心化水平扩展的协调原语。
 * 认领 = 候选查询 + 逐条条件 UPDATE（affectedRows==1 才算抢到），多实例并发不重复消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventMapper eventMapper;
    private final EmonLensProperties properties;

    public void enqueue(EventType eventType, String workspaceId, String agentKey, String payload) {
        LocalDateTime now = LocalDateTime.now();
        EventEntity event = new EventEntity();
        event.setEventType(eventType.name());
        event.setWorkspaceId(workspaceId);
        event.setAgentKey(agentKey);
        event.setPayload(payload);
        event.setStatus(EventEntity.STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextRetryAt(now);
        eventMapper.insert(event);
    }

    /** 认领一批待处理事件，返回本实例抢到的完整事件；候选被其他实例抢走时继续补位直至凑满或无候选 */
    public List<EventEntity> claimBatch(int max) {
        List<EventEntity> claimed = new ArrayList<>();
        int idleRounds = 0;
        while (claimed.size() < max && idleRounds < 3) {
            int before = claimed.size();
            List<String> candidateIds = new ArrayList<>();
            for (EventEntity candidate : eventMapper.selectList(Wrappers.<EventEntity>lambdaQuery()
                    .select(EventEntity::getId)
                    .eq(EventEntity::getStatus, EventEntity.STATUS_PENDING)
                    .le(EventEntity::getNextRetryAt, LocalDateTime.now())
                    .orderByAsc(EventEntity::getCreatedAt)
                    .last("LIMIT " + Math.max(1, max - claimed.size())))) {
                candidateIds.add(candidate.getId());
            }
            for (String id : candidateIds) {
                int won = eventMapper.claimById(id, properties.getInstanceId(), LocalDateTime.now());
                if (won == 1) {
                    claimed.add(eventMapper.selectById(id));
                }
            }
            idleRounds = claimed.size() == before ? idleRounds + 1 : 0;
        }
        return claimed;
    }

    public void complete(String eventId) {
        eventMapper.completeById(eventId, properties.getInstanceId());
    }

    /** 处理失败：按退避策略重回 PENDING；超过最大重试置 DEAD */
    public void fail(EventEntity event) {
        int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        int updated;
        if (retryCount >= properties.getEvent().getMaxRetry()) {
            updated = eventMapper.deadById(event.getId(), properties.getInstanceId(), retryCount);
            if (updated == 1) {
                log.warn("event moved to DEAD, id={}, type={}, retryCount={}",
                        event.getId(), event.getEventType(), retryCount);
            }
        } else {
            LocalDateTime nextRetryAt = LocalDateTime.now()
                    .plusSeconds(properties.getEvent().getRetryBackoffMs() * retryCount / 1000);
            updated = eventMapper.requeueById(event.getId(), properties.getInstanceId(), retryCount, nextRetryAt);
        }
        if (updated == 0) {
            log.warn("event fail-update lost ownership, id={}", event.getId());
        }
    }

    /** 死信重投（人工恢复）：DEAD → PENDING，重试计数清零。@return 是否重投成功 */
    public boolean requeueDead(String workspaceId, String id) {
        return eventMapper.requeueDeadById(id, workspaceId) > 0;
    }

    /**
     * 租约回收：认领超过 leaseMinutes 仍未完成的按原状态重回队列。
     * 处理中的实例不受影响——complete/requeue 都校验 claim_owner，回收后旧实例的完成写入会落空（幂等无害）。
     * lease 必须大于最慢 handler（LLM judge 20s + webhook 5s × 重试），默认取 event.retry-backoff-ms。
     */
    public int reclaimExpiredLeases() {
        long leaseMs = properties.getEvent().getLeaseMs() > 0
                ? properties.getEvent().getLeaseMs()
                : Math.max(properties.getEvent().getRetryBackoffMs(), 120_000L);
        int reclaimed = eventMapper.reclaimExpiredLeases(
                LocalDateTime.now().minusNanos(leaseMs * 1_000_000),
                Math.max(1, properties.getEvent().getMaxRetry()));
        if (reclaimed > 0) {
            log.warn("reclaimed {} expired CLAIMED events (lease={}ms)", reclaimed, leaseMs);
        }
        return reclaimed;
    }

    /** 死信列表（告警页排障）：指定工作空间的 DEAD 事件（新→旧），支持类型过滤与分页 */
    public List<EventEntity> listDead(String workspaceId, String eventType, int offset, int limit) {
        return eventMapper.selectList(Wrappers.<EventEntity>lambdaQuery()
                .eq(EventEntity::getWorkspaceId, workspaceId)
                .eq(EventEntity::getStatus, EventEntity.STATUS_DEAD)
                .eq(eventType != null && !eventType.isEmpty(),
                        EventEntity::getEventType, eventType)
                .orderByDesc(EventEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50)) + " OFFSET " + Math.max(0, offset)));
    }

    /** 死信计数（分页总数；eventType 为空时统计全部） */
    public long countDead(String workspaceId, String eventType) {
        return eventMapper.selectCount(Wrappers.<EventEntity>lambdaQuery()
                .eq(EventEntity::getWorkspaceId, workspaceId)
                .eq(EventEntity::getStatus, EventEntity.STATUS_DEAD)
                .eq(eventType != null && !eventType.isEmpty(),
                        EventEntity::getEventType, eventType));
    }
}
