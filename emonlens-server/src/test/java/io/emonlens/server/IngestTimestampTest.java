package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 上报时间戳归一化：平台时间轴是「服务器本地墙上时间」。
 *
 * <p>naive ISO（2026-10-13T11:56:04）按本地解析；带偏移量（Z / +08:00）必须换算到
 * 服务器时区，而不是归到 UTC——否则 JS SDK（toISOString）与 OTLP 数据会整体偏移时区差。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-ingest-ts;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestTimestampTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    void offsetTimestampNormalizesToServerZone() throws Exception {
        String traceId = "ts-trace-z";
        // 发送「当前 UTC 时刻 + Z」：正确解析后应落在本机 now 附近（归 UTC 会差一个时区差）
        String utcNow = Instant.now().atOffset(ZoneOffset.UTC).format(SEC) + "Z";
        post("/api/v1/ingest/events", List.of(span(traceId, utcNow)));

        LocalDateTime stored = awaitStartTime(traceId);
        long driftSeconds = Math.abs(Duration.between(LocalDateTime.now(ZoneId.systemDefault()), stored).getSeconds());
        assertThat(driftSeconds)
                .as("Z 时间戳应归一到服务器时区（stored=%s，偏差 %s 秒）", stored, driftSeconds)
                .isLessThan(60);
    }

    @Test
    void naiveTimestampKeptAsLocalWallTime() throws Exception {
        String traceId = "ts-trace-naive";
        LocalDateTime local = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(7).withNano(0);
        post("/api/v1/ingest/events", List.of(span(traceId, local.format(SEC))));

        assertThat(awaitStartTime(traceId)).isEqualTo(local);
    }

    private Map<String, Object> span(String traceId, String startTime) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("traceId", traceId);
        map.put("spanId", "root");
        map.put("agentKey", "ts-agent");
        map.put("agentVersion", "v1");
        map.put("spanType", "agent");
        map.put("spanName", "invoke");
        map.put("status", "OK");
        map.put("startTime", startTime);
        map.put("endTime", startTime);
        map.put("input", "t");
        map.put("output", "ok");
        return map;
    }

    private LocalDateTime awaitStartTime(String traceId) throws Exception {
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return !readTree(json).path("data").path("summary").path("startTime").asText().isEmpty();
                    } catch (Exception e) {
                        return false;
                    }
                },
                ASYNC_TIMEOUT_MS);
        JsonNode start = readTree(detail).path("data").path("summary").path("startTime");
        return LocalDateTime.parse(start.asText());
    }
}
