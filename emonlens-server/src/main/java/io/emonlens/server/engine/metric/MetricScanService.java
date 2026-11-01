package io.emonlens.server.engine.metric;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.engine.finding.FindingService;
import io.emonlens.server.storage.mapper.MonitorMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Metric Monitor 周期扫描：评估全部启用的 METRIC monitor 当前窗口。
 * 由 coordinator 的 metric-runner 线程按 emonlens.metric.scan-interval-ms 周期调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricScanService {

    private final MonitorMapper monitorMapper;
    private final MetricMonitorService metricMonitorService;
    private final FindingService findingService;

    /** @return 本轮产出的 Finding 数 */
    public int scanOnce() {
        List<MonitorEntity> monitors = monitorMapper.selectList(Wrappers.<MonitorEntity>lambdaQuery()
                .eq(MonitorEntity::getKind, MonitorEntity.KIND_METRIC)
                .eq(MonitorEntity::getEnabled, 1));
        LocalDateTime now = LocalDateTime.now();
        log.debug("metric scan: {} monitors", monitors.size());
        int produced = 0;
        for (MonitorEntity monitor : monitors) {
            try {
                FindingEntity finding = metricMonitorService.evaluate(monitor, now);
                if (finding != null) {
                    findingService.record(finding);
                    produced++;
                }
            } catch (Exception e) {
                log.error("metric monitor {} evaluation failed", monitor.getId(), e);
            }
        }
        return produced;
    }
}
