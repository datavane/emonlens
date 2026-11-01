package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

/**
 * 跨存储补偿：span 已写入但 summary 丢失/更新失败时（StarRocks 模式的事务边界外窗口），
 * 重试批次全命中幂等去重也必须能重建缺失的 trace summary，trace 不能永久不可见。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-repair;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TraceAssemblyRepairTest extends AbstractIntegrationTest {

    private static final String WS = "ws-default";

    @Resource
    private TraceAssemblyService traceAssemblyService;

    @Resource
    private TraceSummaryMapper traceSummaryMapper;

    @Test
    void missingSummaryIsRebuiltFromDuplicateBatch() {
        String trace = "repair-trace-1";
        SpanEventEntity root = span(trace, 1, 10);
        SpanEventEntity child = span(trace, 3, 8);
        child.setSpanId("child-1");
        child.setParentSpanId("root-1");
        child.setSpanType("llm");
        child.setSpanName("call_model");

        List<TraceSummaryEntity> first = traceAssemblyService.persistBatch(List.of(root, child));
        assertThat(first).hasSize(1);
        assertThat(summary(trace)).isNotNull();
        assertThat(summary(trace).getSpanCount()).isEqualTo(2);

        // 模拟跨存储窗口：span 已在数据面，但 summary 写入失败/崩溃后丢失
        traceSummaryMapper.delete(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, WS)
                .eq(TraceSummaryEntity::getTraceId, trace));
        assertThat(summaryCount(trace)).isZero();

        // 重试批次全命中幂等去重：written 为空，也必须重建 summary
        List<TraceSummaryEntity> repaired = traceAssemblyService.persistBatch(List.of(root, child));
        TraceSummaryEntity rebuilt = summary(trace);
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.getSpanCount()).isEqualTo(2);
        assertThat(rebuilt.getStatus()).isEqualTo(TraceSummaryEntity.STATUS_COMPLETED);
        assertThat(repaired).hasSize(1);
        assertThat(repaired.get(0).getTraceId()).isEqualTo(trace);
    }

    @Test
    void reconcileFixesCountDriftOnCompletedTraces() {
        String trace = "reconcile-trace-1";
        SpanEventEntity root = span(trace, 1, 10);
        SpanEventEntity child = span(trace, 3, 8);
        child.setSpanId("reconcile-child");
        child.setParentSpanId("root-1");
        child.setSpanType("llm");
        child.setSpanName("call_model");
        traceAssemblyService.persistBatch(List.of(root, child));

        // 模拟跨存储丢计数更新：summary 计数落后于真实 span 数
        traceSummaryMapper.update(null, Wrappers.<TraceSummaryEntity>lambdaUpdate()
                .eq(TraceSummaryEntity::getWorkspaceId, WS)
                .eq(TraceSummaryEntity::getTraceId, trace)
                .set(TraceSummaryEntity::getSpanCount, 1)
                .set(TraceSummaryEntity::getErrorCount, 99));

        int repaired = traceAssemblyService.reconcileCompleted(LocalDateTime.now().minusHours(1), 100);
        assertThat(repaired).isEqualTo(1);
        assertThat(summary(trace).getSpanCount()).isEqualTo(2);
        assertThat(summary(trace).getErrorCount()).isZero();
    }

    private SpanEventEntity span(String traceId, int startSecond, int endSecond) {
        SpanEventEntity span = new SpanEventEntity();
        span.setWorkspaceId(WS);
        span.setTraceId(traceId);
        span.setSpanId("root-1");
        span.setAgentKey("repair-agent");
        span.setAgentVersion("v1");
        span.setSpanType("agent");
        span.setSpanName("invoke");
        span.setStatus(SpanEventEntity.STATUS_OK);
        span.setStartTime(LocalDateTime.now().minusSeconds(20 - startSecond));
        span.setEndTime(LocalDateTime.now().minusSeconds(20 - endSecond));
        span.setInputTokens(10L);
        span.setOutputTokens(20L);
        return span;
    }

    private TraceSummaryEntity summary(String traceId) {
        return traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, WS)
                .eq(TraceSummaryEntity::getTraceId, traceId));
    }

    private long summaryCount(String traceId) {
        return traceSummaryMapper.selectCount(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, WS)
                .eq(TraceSummaryEntity::getTraceId, traceId));
    }
}
