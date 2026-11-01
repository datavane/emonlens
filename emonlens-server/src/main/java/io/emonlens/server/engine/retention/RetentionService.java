package io.emonlens.server.engine.retention;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.mapper.ContentMapper;
import io.emonlens.server.storage.mapper.DatasetHealthSnapshotMapper;
import io.emonlens.server.storage.mapper.EventHandlerDeliveryMapper;
import io.emonlens.server.storage.mapper.EventMapper;
import io.emonlens.server.storage.mapper.NotifyRecordMapper;
import io.emonlens.server.storage.mapper.PushRecordMapper;
import io.emonlens.server.storage.mapper.TrustScoreMapper;
import io.emonlens.server.storage.span.SpanEventStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 保留期清理（V1.1 §29.2 默认值）：span 30 天、内容 7 天、终态事件 7 天；0=不清理。
 * 每轮批量删除（上限 retention.batch-size），由 coordinator 周期调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private final SpanEventStore spanStore;
    private final ContentMapper contentMapper;
    private final EventMapper eventMapper;
    private final EventHandlerDeliveryMapper eventHandlerDeliveryMapper;
    private final TrustScoreMapper trustScoreMapper;
    private final NotifyRecordMapper notifyRecordMapper;
    private final PushRecordMapper pushRecordMapper;
    private final DatasetHealthSnapshotMapper healthSnapshotMapper;
    private final EmonLensProperties properties;
    private final SysSettingService sysSettingService;

    public void purgeOnce() {
        int batchSize = properties.getRetention().getBatchSize();
        LocalDateTime now = LocalDateTime.now();
        SysSettingService.Retention db = sysSettingService.getRetention();
        int spanDays = db != null && db.getSpanDays() != null ? db.getSpanDays() : properties.getRetention().getSpanDays();
        if (spanDays > 0) {
            int deleted = spanStore.purgeBefore(now.minusDays(spanDays), batchSize);
            if (deleted > 0) {
                log.info("retention purged {} old spans", deleted);
            }
        }
        int contentDays = db != null && db.getContentDays() != null ? db.getContentDays() : properties.getRetention().getContentDays();
        if (contentDays > 0) {
            int deleted = contentMapper.delete(Wrappers.<io.emonlens.server.storage.entity.ContentEntity>lambdaQuery()
                    .lt(io.emonlens.server.storage.entity.ContentEntity::getCreatedAt, now.minusDays(contentDays))
                    .last("LIMIT " + batchSize));
            if (deleted > 0) {
                log.info("retention purged {} old contents", deleted);
            }
        }
        int eventDays = db != null && db.getEventDays() != null ? db.getEventDays() : properties.getRetention().getEventDays();
        if (eventDays > 0) {
            int deleted = eventMapper.delete(Wrappers.<EventEntity>lambdaQuery()
                    .in(EventEntity::getStatus, EventEntity.STATUS_DONE, EventEntity.STATUS_DEAD)
                    .lt(EventEntity::getCreatedAt, now.minusDays(eventDays))
                    .last("LIMIT " + batchSize));
            if (deleted > 0) {
                log.info("retention purged {} terminal events", deleted);
            }
            int deliveries = eventHandlerDeliveryMapper.delete(
                    Wrappers.<io.emonlens.server.storage.entity.EventHandlerDeliveryEntity>lambdaQuery()
                            .lt(io.emonlens.server.storage.entity.EventHandlerDeliveryEntity::getCreatedAt,
                                    now.minusDays(eventDays))
                            .last("LIMIT " + batchSize));
            if (deliveries > 0) {
                log.info("retention purged {} event handler deliveries", deliveries);
            }
        }
        int scoreDays = db != null && db.getScoreDays() != null
                ? db.getScoreDays() : properties.getRetention().getScoreDays();
        if (scoreDays > 0) {
            int deleted = trustScoreMapper.delete(
                    Wrappers.<io.emonlens.server.storage.entity.TrustScoreEntity>lambdaQuery()
                            .lt(io.emonlens.server.storage.entity.TrustScoreEntity::getComputedAt,
                                    now.minusDays(scoreDays))
                            .last("LIMIT " + batchSize));
            if (deleted > 0) {
                log.info("retention purged {} old score snapshots", deleted);
            }
        }
        int notifyDays = db != null && db.getNotifyRecordDays() != null
                ? db.getNotifyRecordDays() : properties.getRetention().getNotifyRecordDays();
        if (notifyDays > 0) {
            int deleted = notifyRecordMapper.delete(
                    Wrappers.<io.emonlens.server.storage.entity.NotifyRecordEntity>lambdaQuery()
                            .lt(io.emonlens.server.storage.entity.NotifyRecordEntity::getCreatedAt,
                                    now.minusDays(notifyDays))
                            .last("LIMIT " + batchSize));
            if (deleted > 0) {
                log.info("retention purged {} old notify records", deleted);
            }
            int pushes = pushRecordMapper.delete(
                    Wrappers.<io.emonlens.server.storage.entity.PushRecordEntity>lambdaQuery()
                            .lt(io.emonlens.server.storage.entity.PushRecordEntity::getCreatedAt,
                                    now.minusDays(notifyDays))
                            .last("LIMIT " + batchSize));
            if (pushes > 0) {
                log.info("retention purged {} old push records", pushes);
            }
        }
        int healthDays = db != null && db.getHealthSnapshotDays() != null
                ? db.getHealthSnapshotDays() : properties.getRetention().getHealthSnapshotDays();
        if (healthDays > 0) {
            int deleted = healthSnapshotMapper.delete(
                    Wrappers.<io.emonlens.server.storage.entity.DatasetHealthSnapshotEntity>lambdaQuery()
                            .lt(io.emonlens.server.storage.entity.DatasetHealthSnapshotEntity::getCreatedAt,
                                    now.minusDays(healthDays))
                            .last("LIMIT " + batchSize));
            if (deleted > 0) {
                log.info("retention purged {} old dataset health snapshots", deleted);
            }
        }
    }
}
