package io.emonlens.server.engine.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.AbstractIntegrationTest;
import io.emonlens.server.engine.eval.DatasetRunSnapshotService;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.TrustScoreEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.MonitorMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.mapper.TrustScoreMapper;

/**
 * 评分红线：没有任何证据时不得产生 TRUSTED，也不得用 100 代替缺失分数。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-score-evidence;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TrustScoreEvidenceTest extends AbstractIntegrationTest {

    @Resource
    private TrustScoreService trustScoreService;
    @Resource
    private TraceSummaryMapper traceSummaryMapper;
    @Resource
    private MonitorMapper monitorMapper;
    @Resource
    private EvaluatorMapper evaluatorMapper;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private TrustScoreMapper trustScoreMapper;
    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;

    @Test
    void completedDatasetRunContributesOutputEvidenceWithoutDoubleCountingLegacy() {
        String agent = "dataset-evidence-agent";
        String traceId = "trace-dataset-evidence";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-evidence-evaluator");
        // legacy 结果是 PASS，但同 evaluator+trace 的 dataset 新结果是 FAIL；只能计一次，且取 FAIL。
        evalResult(agent, evaluator, traceId, EvaluatorEntity.LABEL_PASS, LocalDateTime.now().minusHours(1));
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_FAIL);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getScore()).isNull();
        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\"")
                .contains("\"evidenceStatus\":\"COMPLETE\"")
                .contains("\"observed\":1")
                .contains("\"score\":0.0");
    }

    @Test
    void completedDatasetPassContributesOutputEvidence() {
        String agent = "dataset-pass-agent";
        String traceId = "trace-dataset-pass";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-pass-evaluator");
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getScore()).isNull();
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\"")
                .contains("\"evidenceStatus\":\"COMPLETE\"")
                .contains("\"observed\":1")
                .contains("\"score\":100.0");
    }

    @Test
    void completeFiveDimensionEvidenceProducesTrustedScore() {
        String agent = "complete-evidence-agent";
        trace(agent, "trace-complete");
        monitor(agent, "SECURITY", "monitor-complete-security");
        monitor(agent, "CONTEXT", "monitor-complete-context");
        EvaluatorEntity evaluator = evaluator("evaluator-complete");
        evalResult(agent, evaluator, "trace-complete", EvaluatorEntity.LABEL_PASS);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getScore()).isNotNull();
        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_TRUSTED);
        assertThat(snapshot.getEvidenceStatus()).isEqualTo(TrustScoreEntity.EVIDENCE_COMPLETE);
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("100");
        assertThat(snapshot.getScoreVersion()).isEqualTo(TrustScoreEntity.SCORE_VERSION_EVIDENCE);
        assertThat(snapshot.getDimensions()).contains("\"evidenceStatus\":\"COMPLETE\"");
    }

    @Test
    void configuredButUnjudgedOutputIsPartialEvidenceAndNeverTrusted() {
        String agent = "partial-evidence-agent";
        trace(agent, "trace-partial");
        monitor(agent, "SECURITY", "monitor-partial-security");
        monitor(agent, "CONTEXT", "monitor-partial-context");
        evaluator("evaluator-partial");

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getScore()).isNull();
        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getEvidenceStatus()).isEqualTo(TrustScoreEntity.EVIDENCE_PARTIAL);
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("80");
    }

    @Test
    void legacyV1ScoresAreNotReturnedByV2Queries() {
        TrustScoreEntity legacy = new TrustScoreEntity();
        legacy.setWorkspaceId("ws-default");
        legacy.setScopeType(TrustScoreEntity.SCOPE_AGENT);
        legacy.setScopeId("legacy-v1-agent");
        legacy.setTimeWindow("24h");
        legacy.setScore(BigDecimal.valueOf(99));
        legacy.setStatus(TrustScoreEntity.STATUS_TRUSTED);
        legacy.setScoreVersion(TrustScoreEntity.SCORE_VERSION_LEGACY);
        legacy.setDimensions("{}");
        legacy.setComputedAt(LocalDateTime.now());
        trustScoreMapper.insert(legacy);

        assertThat(trustScoreService.latest("ws-default", "legacy-v1-agent", "24h")).isNull();
        assertThat(trustScoreService.latestPerAgent("ws-default"))
                .noneMatch(s -> "legacy-v1-agent".equals(s.getScopeId()));
    }

    private DatasetRunEntity datasetRunResult(String agent, String traceId, EvaluatorEntity evaluator, String label) {
        String resultsJson = "[{\"evaluatorId\":\"" + evaluator.getId()
                + "\",\"evaluatorKey\":\"" + evaluator.getEvaluatorKey()
                + "\",\"evaluatorVersion\":1,\"label\":\"" + label
                + "\",\"score\":" + (EvaluatorEntity.LABEL_PASS.equals(label) ? 100 : 0)
                + ",\"reason\":\"dataset test\"}]";
        return datasetRunResult(agent, traceId, evaluator, label, DatasetRunEntity.STATUS_COMPLETED, resultsJson);
    }

    private DatasetRunEntity datasetRunResult(String agent, String traceId, EvaluatorEntity evaluator,
                                              String label, String status, String resultsJson) {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("dataset-evidence");
        run.setEvaluatorId(evaluator.getId());
        run.setEvaluatorKey(evaluator.getEvaluatorKey());
        run.setStatus(status);
        run.setItemCount(1);
        run.setPassCount(EvaluatorEntity.LABEL_PASS.equals(label) ? 1 : 0);
        run.setFailCount(EvaluatorEntity.LABEL_FAIL.equals(label) ? 1 : 0);
        run.setRunMode("TRACE");
        run.setRequestKey("dataset-evidence-" + UUID.randomUUID());
        run.setRequestHash("test");
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId(UUID.randomUUID().toString());
        item.setTraceId(traceId);
        item.setSourceTraceId(traceId);
        item.setAgentKey(agent);
        item.setAgentVersion("v1");
        item.setLabel(label);
        item.setSampleSnapshotJson("{\"agentKey\":\"" + agent + "\",\"agentVersion\":\"v1\"}");
        item.setResultDetailsJson("{}");
        item.setEvaluatorResultsJson(resultsJson);
        datasetRunItemMapper.insert(item);
        return run;
    }

    private void trace(String agent, String traceId) {
        TraceSummaryEntity trace = new TraceSummaryEntity();
        trace.setWorkspaceId("ws-default");
        trace.setAgentKey(agent);
        trace.setAgentVersion("v1");
        trace.setTraceId(traceId);
        trace.setStatus(TraceSummaryEntity.STATUS_COMPLETED);
        trace.setSpanCount(1);
        trace.setErrorCount(0);
        trace.setStartTime(LocalDateTime.now());
        trace.setEndTime(LocalDateTime.now());
        trace.setDurationMs(1L);
        trace.setLastSpanAt(LocalDateTime.now());
        trace.setCompletedAt(LocalDateTime.now());
        traceSummaryMapper.insert(trace);
    }

    private void monitor(String agent, String dimension, String key) {
        MonitorEntity monitor = new MonitorEntity();
        monitor.setWorkspaceId("ws-default");
        monitor.setAgentKey(agent);
        monitor.setMonitorKey(key);
        monitor.setKind(MonitorEntity.KIND_VALIDATION);
        monitor.setSpecJson("{}");
        monitor.setSeverity("HIGH");
        monitor.setDimension(dimension);
        monitor.setEnabled(1);
        monitor.setVersion(1);
        monitor.setCreatedAt(LocalDateTime.now());
        monitor.setUpdatedAt(LocalDateTime.now());
        monitorMapper.insert(monitor);
    }

    private EvaluatorEntity evaluator(String key) {
        EvaluatorEntity evaluator = new EvaluatorEntity();
        evaluator.setWorkspaceId("ws-default");
        evaluator.setEvaluatorKey(key);
        evaluator.setType(EvaluatorEntity.TYPE_EXPRESSION);
        evaluator.setSpecJson("{\"passWhen\":\"true\"}");
        evaluator.setEnabled(1);
        evaluator.setVersion(1);
        evaluator.setCreatedAt(LocalDateTime.now());
        evaluator.setUpdatedAt(LocalDateTime.now());
        evaluatorMapper.insert(evaluator);
        return evaluator;
    }

    private void evalResult(String agent, EvaluatorEntity evaluator, String traceId, String label) {
        evalResult(agent, evaluator, traceId, label, LocalDateTime.now());
    }

    private void evalResult(String agent, EvaluatorEntity evaluator, String traceId, String label,
                            LocalDateTime createdAt) {
        evalResult(agent, evaluator, traceId, label, createdAt, 1);
    }

    private void evalResult(String agent, EvaluatorEntity evaluator, String traceId, String label,
                            LocalDateTime createdAt, int attempt) {
        EvalResultEntity result = new EvalResultEntity();
        result.setWorkspaceId("ws-default");
        result.setEvaluatorId(evaluator.getId());
        result.setEvaluatorKey(evaluator.getEvaluatorKey());
        result.setEvaluatorVersion(evaluator.getVersion());
        result.setAgentKey(agent);
        result.setTraceId(traceId);
        result.setAttempt(attempt);
        result.setLabel(label);
        result.setScore(BigDecimal.valueOf(100));
        result.setReason("test");
        result.setCreatedAt(createdAt);
        evalResultMapper.insert(result);
    }

    @Test
    void duplicateDatasetResultsForSameEvaluatorAndTraceCountOnce() {
        String agent = "dataset-dedupe-agent";
        String traceId = "trace-dataset-dedupe";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-dedupe-evaluator");
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_FAIL);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\",\"observed\":1,\"total\":1");
    }

    @Test
    void datasetRunForAnotherAgentDoesNotLeakEvidence() {
        String agent = "dataset-target-agent";
        String traceId = "trace-dataset-target";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-target-evaluator");
        datasetRunResult("dataset-other-agent", traceId, evaluator, EvaluatorEntity.LABEL_PASS);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\",\"observed\":0,\"total\":0");
    }

    @Test
    void nonCompletedDatasetRunDoesNotContributeEvidence() {
        String agent = "dataset-running-agent";
        String traceId = "trace-dataset-running";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-running-evaluator");
        DatasetRunEntity run = datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        datasetRunMapper.updateById(run);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getDimensions()).contains("\"evidenceStatus\":\"NO_DATA\"");
    }

    @Test
    void replayDatasetEvidenceUsesPersistedAgentAttribution() {
        String agent = DatasetRunSnapshotService.REPLAY_AGENT_KEY;
        String traceId = "trace-dataset-replay";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-replay-evaluator");
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getScore()).isNull();
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\"")
                .contains("\"observed\":1")
                .contains("\"score\":100.0");
    }

    @Test
    void malformedDatasetItemDoesNotHideOtherEvidence() {
        String agent = "dataset-malformed-agent";
        String traceId = "trace-dataset-malformed";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-malformed-evaluator");
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS,
                DatasetRunEntity.STATUS_COMPLETED, "[1,\"x\",null]");
        datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\"")
                .contains("\"observed\":1")
                .contains("\"score\":100.0");
    }

    @Test
    void newerUnknownLegacyResultOverridesOlderPass() {
        String agent = "dataset-newer-unknown-agent";
        String traceId = "trace-dataset-newer-unknown";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-newer-unknown-evaluator");
        evalResult(agent, evaluator, traceId, EvaluatorEntity.LABEL_PASS, LocalDateTime.now().minusHours(1));
        evalResult(agent, evaluator, traceId, "UNKNOWN", LocalDateTime.now(), 2);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\",\"observed\":0,\"total\":1");
    }

    @Test
    void historicalItemWithoutAgentColumnFallsBackToSnapshot() {
        String agent = "dataset-historical-agent";
        String traceId = "trace-dataset-historical";
        trace(agent, traceId);
        EvaluatorEntity evaluator = evaluator("dataset-historical-evaluator");
        DatasetRunEntity run = datasetRunResult(agent, traceId, evaluator, EvaluatorEntity.LABEL_PASS);
        DatasetRunItemEntity item = datasetRunItemMapper.selectOne(Wrappers.<DatasetRunItemEntity>lambdaQuery()
                .eq(DatasetRunItemEntity::getRunId, run.getId()));
        item.setAgentKey(null);
        item.setAgentVersion(null);
        datasetRunItemMapper.updateById(item);

        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", agent, 24);

        assertThat(snapshot.getCoverage()).isEqualByComparingTo("60");
        assertThat(snapshot.getDimensions())
                .contains("\"basis\":\"eval_pass_rate\",\"observed\":1,\"total\":1");
    }

    @Test
    void noEvidenceIsInsufficientInsteadOfTrusted() {
        TrustScoreEntity snapshot = trustScoreService.recompute("ws-default", "no-evidence-agent", 24);

        assertThat(snapshot.getScore()).isNull();
        assertThat(snapshot.getStatus()).isEqualTo(TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE);
        assertThat(snapshot.getEvidenceStatus()).isEqualTo(TrustScoreEntity.EVIDENCE_NONE);
        assertThat(snapshot.getCoverage()).isEqualByComparingTo("0");
        assertThat(snapshot.getScoreVersion()).isEqualTo(TrustScoreEntity.SCORE_VERSION_EVIDENCE);
        assertThat(snapshot.getDimensions()).contains("\"score\":null");
        assertThat(snapshot.getDimensions()).contains("NO_DATA");
    }
}
