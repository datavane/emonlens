package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Metric Monitor 端到端：p95 duration 越限 + 连续窗口确认 → MONITOR_METRIC Finding。
 * 独立 H2 库避免跨上下文 poller 竞争；扫描间隔缩短到 500ms 便于测试。
 */
@SpringBootTest(properties = {
        "emonlens.metric.scan-interval-ms=500",
        "spring.datasource.url=jdbc:h2:mem:emonlens-metric;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class MetricMonitorFlowTest extends AbstractIntegrationTest {

    private static final String AGENT = "metric-agent";

    @Test
    void metricMonitorTriggersFindingAfterConsecutiveWindows() throws Exception {
        // 1. 创建 METRIC monitor：窗口 p95 duration > 1000ms，连续 2 个窗口告警
        Map<String, Object> spec = new HashMap<>();
        spec.put("metric", "duration_ms");
        spec.put("aggregation", "p95");
        spec.put("windowMinutes", 1);
        spec.put("thresholdType", "static");
        spec.put("thresholdValue", 1000.0);
        spec.put("direction", "above");
        spec.put("consecutiveWindows", 2);
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", AGENT);
        monitorRequest.put("monitorKey", "latency-p95");
        monitorRequest.put("kind", "METRIC");
        monitorRequest.put("spec", spec);
        monitorRequest.put("severity", "HIGH");
        assertThat(readTree(postApi("/api/v1/monitors", monitorRequest)).path("code").asInt()).isZero();

        // 2. ingest 一条 4 秒的慢 trace（时间为"现在"，保证落在扫描窗口内）
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        Map<String, Object> slowSpan = new HashMap<>();
        slowSpan.put("traceId", "metric-trace-1");
        slowSpan.put("spanId", "root");
        slowSpan.put("agentKey", AGENT);
        slowSpan.put("spanType", "agent");
        slowSpan.put("spanName", "invoke_agent");
        slowSpan.put("startTime", now.minusSeconds(6).format(fmt));
        slowSpan.put("endTime", now.minusSeconds(2).format(fmt));
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(slowSpan);
        post("/api/v1/ingest/events", spans);

        // 3. 连续窗口扫描后 → MONITOR_METRIC Finding
        String findings = await(
                () -> {
                    try {
                        return get("/api/v1/findings?agentKey=" + AGENT + "&type=MONITOR_METRIC");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode finding = readSafely(findings).path("data").path("records").get(0);
        assertThat(finding.get("detectorId").asText()).isEqualTo("latency-p95");
        assertThat(finding.get("dimension").asText()).isEqualTo("PERFORMANCE");
        assertThat(finding.get("message").asText()).contains("duration_ms");
    }

    @Test
    void monitorDeleteRemovesRuleButKeepsFindings() throws Exception {
        Map<String, Object> spec = new HashMap<>();
        spec.put("where", "status == 'ERROR'");
        Map<String, Object> req = new HashMap<>();
        req.put("agentKey", AGENT);
        req.put("monitorKey", "del-check");
        req.put("kind", "VALIDATION");
        req.put("spec", spec);
        req.put("severity", "HIGH");
        String id = readTree(postApi("/api/v1/monitors", req)).path("data").path("id").asText();

        // 删规则 → 规则消失；404 语义（业务码非 0）
        assertThat(readTree(deleteApi("/api/v1/monitors/" + id)).path("code").asInt()).isZero();
        JsonNode after = readTree(get("/api/v1/monitors?agentKey=" + AGENT));
        assertThat(after.path("data").path("records").toString()).doesNotContain("del-check");

        // 再删一次 → 明确的 not found，而不是 500
        JsonNode again = readTree(deleteApi("/api/v1/monitors/" + id));
        assertThat(again.path("code").asInt()).isNotZero();
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
