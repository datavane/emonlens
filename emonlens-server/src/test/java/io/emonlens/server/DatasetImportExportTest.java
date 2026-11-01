package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 数据集 CSV/JSONL 导入导出：dry-run、幂等、行级错误、atomic、导出正文。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-import;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class DatasetImportExportTest extends AbstractIntegrationTest {

    @Test
    void jsonlDryRunImportIsIdempotentAndReportsRowErrors() throws Exception {
        String datasetId = createDataset("import-jsonl");
        String jsonl = String.join("\n",
                "{\"input\":\"q1\",\"expected_output\":\"a1\"}",
                "{\"input\":\"q2\",\"expected_output\":\"a2\",\"schema_version\":2,"
                        + "\"retrieval_context\":[{\"id\":\"d1\",\"content\":\"ctx\"}],"
                        + "\"expected_tools\":[{\"name\":\"query_order\",\"required\":true}]}",
                "{\"input\":\"q1\",\"expected_output\":\"a1\"}",
                "{\"input\":\"q3\",\"turns\":[{\"role\":\"robot\",\"content\":\"x\"}]}");

        JsonNode dry = importItems(datasetId, "jsonl", jsonl, true, false);
        assertThat(dry.path("code").asInt()).isZero();
        assertThat(dry.path("data").path("total").asInt()).isEqualTo(4);
        assertThat(dry.path("data").path("created").asInt()).isZero();
        assertThat(dry.path("data").path("wouldCreate").asInt()).isEqualTo(2);
        assertThat(dry.path("data").path("duplicated").asInt()).isEqualTo(1);
        assertThat(dry.path("data").path("invalid").asInt()).isEqualTo(1);
        assertThat(dry.path("data").path("errors").get(0).path("row").asInt()).isEqualTo(4);

        JsonNode real = importItems(datasetId, "jsonl", jsonl, false, false);
        assertThat(real.path("data").path("created").asInt()).isEqualTo(2);
        assertThat(real.path("data").path("wouldCreate").asInt()).isEqualTo(2);
        assertThat(real.path("data").path("duplicated").asInt()).isEqualTo(1);
        assertThat(real.path("data").path("invalid").asInt()).isEqualTo(1);

        JsonNode again = importItems(datasetId, "jsonl", jsonl, false, false);
        assertThat(again.path("data").path("created").asInt()).isZero();
        assertThat(again.path("data").path("wouldCreate").asInt()).isZero();
        assertThat(again.path("data").path("duplicated").asInt()).isEqualTo(3);

        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId));
        assertThat(detail.path("data").path("items")).hasSize(2);
    }

    @Test
    void atomicImportRejectsWholeBatchOnInvalidRow() throws Exception {
        String datasetId = createDataset("import-atomic");
        String jsonl = String.join("\n",
                "{\"input\":\"ok\"}",
                "{\"input\":\"bad\",\"retrieval_context\":[{\"content\":\"\"}]}");
        JsonNode result = importItems(datasetId, "jsonl", jsonl, false, true);
        assertThat(result.path("code").asInt()).isEqualTo(40000);
        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId));
        assertThat(detail.path("data").path("items")).isEmpty();
    }

    @Test
    void csvImportAndExportRoundTrip() throws Exception {
        String datasetId = createDataset("import-csv");
        String csv = "input,expected_output,retrieval_context,purpose_tags,risk_level\n"
                + "\"q, with comma\",\"a1\",\"[{\"\"id\"\":\"\"d1\"\",\"\"content\"\":\"\"ctx\"\"}]\","
                + "regression,HIGH\n";
        JsonNode imported = importItems(datasetId, "csv", csv, false, false);
        assertThat(imported.path("data").path("created").asInt()).isEqualTo(1);

        String exportedCsv = get("/api/v1/datasets/" + datasetId + "/items/export?format=csv");
        assertThat(exportedCsv).contains("q, with comma");
        assertThat(exportedCsv).contains("retrieval_context");

        String exportedJsonl = get("/api/v1/datasets/" + datasetId + "/items/export?format=jsonl");
        assertThat(exportedJsonl).contains("\"input\":\"q, with comma\"");
        assertThat(exportedJsonl).contains("ctx");
        assertThat(exportedJsonl).contains("\"purpose_tags\":\"regression\"");

        // 导出 CSV 带 BOM；直接回导必须能被识别，不能被首列表头 BOM 破坏
        String targetId = createDataset("import-csv-roundtrip");
        JsonNode reimported = importItems(targetId, "csv", exportedCsv, false, false);
        assertThat(reimported.path("data").path("created").asInt()).isEqualTo(1);
        assertThat(reimported.path("data").path("invalid").asInt()).isZero();
    }

    @Test
    void jsonlMetadataObjectRoundTrips() throws Exception {
        String sourceId = createDataset("import-meta");
        String jsonl = "{\"input\":\"q-meta\",\"metadata\":{\"lang\":\"zh\",\"source\":\"faq\"}}\n";
        assertThat(importItems(sourceId, "jsonl", jsonl, false, false)
                .path("data").path("created").asInt()).isEqualTo(1);

        JsonNode detail = readTree(get("/api/v1/datasets/" + sourceId));
        JsonNode metadata = objectMapper.readTree(
                detail.path("data").path("items").get(0).path("metadataJson").asText());
        assertThat(metadata.path("lang").asText()).isEqualTo("zh");

        // 导出 JSONL 的 metadata 是对象；重新导入必须按 JSON 解析，而不是 Java Map.toString()
        String exported = get("/api/v1/datasets/" + sourceId + "/items/export?format=jsonl");
        String targetId = createDataset("import-meta-roundtrip");
        JsonNode imported = importItems(targetId, "jsonl", exported, false, false);
        assertThat(imported.path("data").path("created").asInt()).isEqualTo(1);
        JsonNode target = readTree(get("/api/v1/datasets/" + targetId));
        JsonNode targetMetadata = objectMapper.readTree(
                target.path("data").path("items").get(0).path("metadataJson").asText());
        assertThat(targetMetadata.path("source").asText()).isEqualTo("faq");
    }

    @Test
    void oversizedBatchIsRejected() throws Exception {
        String datasetId = createDataset("import-too-many");
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i <= 1000; i++) {
            jsonl.append("{\"input\":\"q").append(i).append("\"}\n");
        }
        JsonNode result = importItems(datasetId, "jsonl", jsonl.toString(), true, false);
        assertThat(result.path("code").asInt()).isEqualTo(40000);
        assertThat(result.path("message").asText()).contains("最多导入");
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode importItems(String datasetId, String format, String content,
                                 boolean dryRun, boolean atomic) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("format", format);
        body.put("content", content);
        body.put("dryRun", dryRun);
        body.put("atomic", atomic);
        return readTree(postApi("/api/v1/datasets/" + datasetId + "/items/import", body));
    }
}
