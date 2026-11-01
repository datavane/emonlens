package io.emonlens.server.engine.eval;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 每日保鲜扫描：仅对已配置 blueprint 的数据集写快照，不自动删除/改动样本。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetHealthScheduler {

    private final DatasetHealthService datasetHealthService;

    @Scheduled(cron = "${emonlens.dataset-health.cron:0 30 2 * * *}")
    public void scan() {
        int quarantined = datasetHealthService.autoQuarantineStaleAll();
        int snapshots = datasetHealthService.scanAll();
        log.info("dataset health cron finished, autoQuarantined={}, snapshots={}",
                quarantined, snapshots);
    }
}
