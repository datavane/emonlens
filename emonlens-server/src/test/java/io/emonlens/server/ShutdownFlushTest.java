package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import io.emonlens.server.coordinator.BackgroundJobManager;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.storage.span.SpanEventStore;

/**
 * 优雅停机刷盘：worker 禁用（api-only 部署形态）时 ingest 队列数据在 stop() 后落库，不随重启丢失。
 * worker 禁用保证测试确定性（无后台写线程竞争队列）。
 */
@SpringBootTest(properties = {
        "emonlens.worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:emonlens-shutdown;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.platform=h2",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql"})
class ShutdownFlushTest {

    @Resource
    private IngestService ingestService;
    @Resource
    private BackgroundJobManager backgroundJobManager;
    @Resource
    private SpanEventStore spanStore;

    @Test
    void stopFlushesIngestQueueToStore() {
        // api-only 形态：不跑后台线程，但 SmartLifecycle 必须仍处于 running，
        // 否则 Spring 不会在停机时调 stop()，队列里的 span 会全部丢失
        // （此前 isRunning() 为 false，实测优雅停机后 span 仍未入库）
        assertThat(backgroundJobManager.isRunning()).isTrue();
        ingestService.accept(new TrustContext("test", "ws-default"), List.of(
                spanOf("sd-flush-1", "root", "agent"),
                spanOf("sd-flush-1", "s1", "tool")));
        assertThat(ingestService.queueSize()).isEqualTo(2);       // 队列中等待落盘

        backgroundJobManager.stop();                              // 优雅停机

        assertThat(ingestService.queueSize()).isZero();           // 队列已刷盘
        Long total = spanStore.search(SpanEventStore.SpanQuery.builder()
                .traceId("sd-flush-1")
                .countOnly(true).build()).getTotal();
        assertThat(total).isEqualTo(2);                           // 数据在库
        assertThat(backgroundJobManager.isRunning()).isFalse();   // stop 后回到非运行态
    }

    private SpanEventDto spanOf(String traceId, String spanId, String spanType) {
        SpanEventDto dto = new SpanEventDto();
        dto.setTraceId(traceId);
        dto.setSpanId(spanId);
        dto.setParentSpanId("root".equals(spanId) ? null : "root");
        dto.setAgentKey("flush-agent");
        dto.setAgentVersion("v1");
        dto.setSpanType(spanType);
        dto.setSpanName("op-" + spanId);
        dto.setStartTime("2026-10-03T10:00:00");
        dto.setEndTime("2026-10-03T10:00:01");
        return dto;
    }
}
