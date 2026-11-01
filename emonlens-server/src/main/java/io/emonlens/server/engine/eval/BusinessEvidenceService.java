package io.emonlens.server.engine.eval;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.api.BusinessExperimentController.CreateRequest;
import io.emonlens.server.api.BusinessExperimentController.EvidenceRequest;
import io.emonlens.server.storage.entity.BusinessEvidenceEntity;
import io.emonlens.server.storage.entity.BusinessEvidenceNonceEntity;
import io.emonlens.server.storage.entity.BusinessSourceCredentialEntity;
import io.emonlens.server.storage.entity.BusinessExperimentEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.mapper.BusinessEvidenceMapper;
import io.emonlens.server.storage.mapper.BusinessEvidenceNonceMapper;
import io.emonlens.server.storage.mapper.BusinessExperimentMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

import lombok.RequiredArgsConstructor;

/**
 * 外部业务实验证据（D5）：只接收版本化报告并做质量校验，不在平台内伪造因果。
 * 同一 experiment+reportVersion 不可覆盖；同一 payload 重复导入幂等。
 */
@Service
@RequiredArgsConstructor
public class BusinessEvidenceService {

    private static final List<String> DESIGN_TYPES = List.of("RANDOMIZED", "OBSERVATIONAL");
    private static final double ALPHA = 0.05;
    private static final int MAX_COMPARISONS = 20;

    private final BusinessExperimentMapper experimentMapper;
    private final BusinessEvidenceMapper evidenceMapper;
    private final BusinessEvidenceNonceMapper nonceMapper;
    private final BusinessSourceCredentialService credentialService;
    private final DatasetRunMapper datasetRunMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> create(String workspaceId, CreateRequest request) {
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "request is required");
        }
        String source = required(request.getSource(), "source", 100);
        String externalId = required(request.getExternalExperimentId(), "externalExperimentId", 200);
        String primaryMetric = required(request.getPrimaryMetric(), "primaryMetric", 100);
        String designType = required(request.getDesignType(), "designType", 24).toUpperCase();
        if (!DESIGN_TYPES.contains(designType)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "designType 只允许 RANDOMIZED/OBSERVATIONAL");
        }
        String runId = trimToNull(request.getRunId());
        String candidate = trimToNull(request.getCandidateFingerprint());
        if (runId != null) {
            DatasetRunEntity run = datasetRunMapper.selectById(runId);
            if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "run not found");
            }
            if (candidate == null) {
                candidate = run.getCandidateFingerprint();
            }
        }

        BusinessExperimentEntity existing = experimentMapper.selectOne(
                Wrappers.<BusinessExperimentEntity>lambdaQuery()
                        .eq(BusinessExperimentEntity::getWorkspaceId, workspaceId)
                        .eq(BusinessExperimentEntity::getSource, source)
                        .eq(BusinessExperimentEntity::getExternalExperimentId, externalId));
        if (existing != null) {
            return detail(workspaceId, existing.getId());
        }

        BusinessExperimentEntity entity = new BusinessExperimentEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setSource(source);
        entity.setExternalExperimentId(externalId);
        entity.setAgentKey(trimToNull(request.getAgentKey()));
        entity.setRunId(runId);
        entity.setCandidateFingerprint(candidate);
        entity.setControlFingerprint(trimToNull(request.getControlFingerprint()));
        entity.setTarget(trimToNull(request.getTarget()));
        entity.setDesignType(designType);
        entity.setDesignJson(toJson(request.getDesign() == null ? Map.of() : request.getDesign()));
        entity.setPrimaryMetric(primaryMetric);
        entity.setGuardrailsJson(toJson(request.getGuardrails() == null
                ? List.of() : request.getGuardrails()));
        int minSample = request.getMinSampleSize() == null ? 0 : request.getMinSampleSize();
        if (minSample < 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "minSampleSize 不能为负");
        }
        entity.setMinSampleSize(minSample);
        entity.setRequireSignature(Boolean.TRUE.equals(request.getRequireSignature()));
        entity.setStartedAt(request.getStartedAt());
        entity.setEndedAt(request.getEndedAt());
        entity.setCreatedBy(request.getCreatedBy());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        experimentMapper.insert(entity);
        return detail(workspaceId, entity.getId());
    }

    @Transactional
    public Map<String, Object> addEvidence(String workspaceId, String experimentId,
                                           EvidenceRequest request) {
        BusinessExperimentEntity experiment = requireExperiment(workspaceId, experimentId);
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "request is required");
        }
        String reportVersion = required(request.getReportVersion(), "reportVersion", 64);
        if (request.getMetrics() == null || request.getMetrics().isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "metrics is required");
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("design", request.getDesign() == null ? Map.of() : request.getDesign());
        canonical.put("metrics", request.getMetrics());
        canonical.put("qualityChecks", request.getQualityChecks() == null
                ? Map.of() : request.getQualityChecks());
        canonical.put("reportedAt", request.getReportedAt());
        String payloadHash = Sha256Utils.hash(canonicalJson(canonical));
        SignatureProof proof = verifySignature(workspaceId, experiment, request, reportVersion, payloadHash);
        if (Boolean.TRUE.equals(experiment.getRequireSignature()) && !proof.verified) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "该实验要求证据签名");
        }

        BusinessEvidenceEntity sameVersion = evidenceMapper.selectOne(
                Wrappers.<BusinessEvidenceEntity>lambdaQuery()
                        .eq(BusinessEvidenceEntity::getExperimentId, experimentId)
                        .eq(BusinessEvidenceEntity::getReportVersion, reportVersion));
        if (sameVersion != null) {
            if (Objects.equals(sameVersion.getPayloadHash(), payloadHash)) {
                return detail(workspaceId, experimentId);
            }
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "reportVersion 已存在且 payload 不同；证据版本不可覆盖");
        }
        BusinessEvidenceEntity samePayload = evidenceMapper.selectOne(
                Wrappers.<BusinessEvidenceEntity>lambdaQuery()
                        .eq(BusinessEvidenceEntity::getExperimentId, experimentId)
                        .eq(BusinessEvidenceEntity::getPayloadHash, payloadHash));
        if (samePayload != null) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "相同 payload 已以 reportVersion=" + samePayload.getReportVersion() + " 导入");
        }

        Map<String, Object> checks = qualityChecks(experiment, request.getMetrics(),
                request.getQualityChecks());
        List<Map<String, Object>> comparisons = multipleComparisons(
                experiment, request.getMetrics(), checks);
        String decision = decide(experiment.getDesignType(), checks);
        BusinessEvidenceEntity entity = new BusinessEvidenceEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setExperimentId(experimentId);
        entity.setReportVersion(reportVersion);
        entity.setPayloadHash(payloadHash);
        entity.setDesignJson(toJson(request.getDesign() == null
                ? parseMap(experiment.getDesignJson()) : request.getDesign()));
        entity.setMetricsJson(toJson(request.getMetrics()));
        entity.setQualityChecksJson(toJson(checks));
        if (comparisons != null) {
            entity.setComparisonsJson(toJson(comparisons));
            entity.setAdjustmentMethod("HOLM");
        }
        entity.setDecision(decision);
        entity.setReportedAt(request.getReportedAt() == null ? LocalDateTime.now() : request.getReportedAt());
        entity.setReceivedBy(request.getReceivedBy());
        entity.setReceivedAt(LocalDateTime.now());
        entity.setSourceKeyId(proof.keyId);
        entity.setSignature(proof.signature);
        entity.setSignedAt(proof.signedAt);
        entity.setNonce(proof.nonce);
        entity.setSignatureVerified(proof.verified);
        if (proof.verified) {
            BusinessEvidenceNonceEntity nonce = new BusinessEvidenceNonceEntity();
            nonce.setWorkspaceId(workspaceId);
            nonce.setSourceKeyId(proof.keyId);
            nonce.setNonce(proof.nonce);
            nonce.setCreatedAt(LocalDateTime.now());
            try {
                nonceMapper.insert(nonce);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new EmonLensException(ErrorCode.CONFLICT, "签名 nonce 已被使用");
            }
        }
        evidenceMapper.insert(entity);
        return detail(workspaceId, experimentId);
    }

    public Map<String, Object> detail(String workspaceId, String experimentId) {
        BusinessExperimentEntity experiment = requireExperiment(workspaceId, experimentId);
        List<BusinessEvidenceEntity> evidence = evidenceMapper.selectList(
                Wrappers.<BusinessEvidenceEntity>lambdaQuery()
                        .eq(BusinessEvidenceEntity::getExperimentId, experimentId)
                        .orderByDesc(BusinessEvidenceEntity::getReceivedAt));
        Map<String, Object> map = experimentMap(experiment);
        map.put("evidenceCount", evidence.size());
        map.put("latestEvidence", evidence.isEmpty() ? null : evidenceMap(evidence.get(0)));
        map.put("evidence", evidence.stream().map(this::evidenceMap).collect(java.util.stream.Collectors.toList()));
        return map;
    }

    public List<Map<String, Object>> list(String workspaceId, String runId) {
        List<BusinessExperimentEntity> experiments = experimentMapper.selectList(
                Wrappers.<BusinessExperimentEntity>lambdaQuery()
                        .eq(BusinessExperimentEntity::getWorkspaceId, workspaceId)
                        .eq(trimToNull(runId) != null, BusinessExperimentEntity::getRunId, trimToNull(runId))
                        .orderByDesc(BusinessExperimentEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BusinessExperimentEntity experiment : experiments) {
            BusinessEvidenceEntity latest = evidenceMapper.selectOne(
                    Wrappers.<BusinessEvidenceEntity>lambdaQuery()
                            .eq(BusinessEvidenceEntity::getExperimentId, experiment.getId())
                            .orderByDesc(BusinessEvidenceEntity::getReceivedAt)
                            .last("LIMIT 1"));
            Map<String, Object> map = experimentMap(experiment);
            map.put("latestEvidence", latest == null ? null : evidenceMap(latest));
            result.add(map);
        }
        return result;
    }

    private SignatureProof verifySignature(String workspaceId, BusinessExperimentEntity experiment,
                                           EvidenceRequest request, String reportVersion,
                                           String payloadHash) {
        boolean any = trimToNull(request.getSourceKeyId()) != null
                || trimToNull(request.getSignature()) != null
                || trimToNull(request.getSignedAt()) != null
                || trimToNull(request.getNonce()) != null;
        if (!any) {
            return SignatureProof.unsigned();
        }
        String keyId = required(request.getSourceKeyId(), "sourceKeyId", 64);
        String signature = required(request.getSignature(), "signature", 1024);
        String signedAt = required(request.getSignedAt(), "signedAt", 64);
        String nonce = required(request.getNonce(), "nonce", 128);
        if (!nonce.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "nonce 必须为 8..128 位 URL-safe 字符");
        }
        OffsetDateTime signedTime;
        try {
            signedTime = OffsetDateTime.parse(signedAt);
        } catch (DateTimeParseException e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "signedAt 必须是 RFC3339");
        }
        if (Duration.between(signedTime, OffsetDateTime.now()).abs().toMinutes() > 5) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "signedAt 超出 ±5 分钟窗口");
        }
        BusinessSourceCredentialEntity credential = credentialService.active(
                workspaceId, keyId, experiment.getSource());
        String signingString = experiment.getId() + "\n" + keyId + "\n" + reportVersion + "\n"
                + signedAt + "\n" + nonce + "\n" + payloadHash;
        if (!credentialService.verify(credential.getPublicKey(), signingString, signature)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "证据签名校验失败");
        }
        return new SignatureProof(true, keyId, signature, signedAt, nonce);
    }

    private static final class SignatureProof {
        private final boolean verified;
        private final String keyId;
        private final String signature;
        private final String signedAt;
        private final String nonce;

        private SignatureProof(boolean verified, String keyId, String signature,
                               String signedAt, String nonce) {
            this.verified = verified;
            this.keyId = keyId;
            this.signature = signature;
            this.signedAt = signedAt;
            this.nonce = nonce;
        }

        private static SignatureProof unsigned() {
            return new SignatureProof(false, null, null, null, null);
        }
    }

    private BusinessExperimentEntity requireExperiment(String workspaceId, String experimentId) {
        BusinessExperimentEntity experiment = experimentMapper.selectById(experimentId);
        if (experiment == null || !Objects.equals(experiment.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "business experiment not found");
        }
        return experiment;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> multipleComparisons(BusinessExperimentEntity experiment,
                                                          Map<String, Object> metrics,
                                                          Map<String, Object> checks) {
        Object raw = metrics == null ? null : metrics.get("comparisons");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "metrics.comparisons 必须是非空数组");
        }
        List<?> rows = (List<?>) raw;
        if (rows.size() > MAX_COMPARISONS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "metrics.comparisons 最多 " + MAX_COMPARISONS + " 项");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object row : rows) {
            if (!(row instanceof Map)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "metrics.comparisons 每项必须是对象");
            }
            Map<String, Object> c = (Map<String, Object>) row;
            String metric = required(asString(c.get("metric")), "comparisons.metric", 100);
            if (!names.add(metric)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "metrics.comparisons 存在重复指标: " + metric);
            }
            double p = doubleValue(c.get("pValue"), -1);
            if (p < 0 || p > 1) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "comparisons.pValue 必须在 0..1: " + metric);
            }
            boolean guardrail = Boolean.TRUE.equals(c.get("guardrail"));
            double lift = doubleValue(first(c, "relativeLift", "lift"), 0);
            Double lower = optionalDouble(first(c, "ciLower", "ci_lower"));
            Double upper = optionalDouble(first(c, "ciUpper", "ci_upper"));
            if ((lower == null) != (upper == null)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "comparisons 的 ciLower/ciUpper 必须同时提供: " + metric);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", metric);
            item.put("pValue", p);
            item.put("relativeLift", lift);
            item.put("ciLower", lower);
            item.put("ciUpper", upper);
            item.put("guardrail", guardrail);
            item.put("significant", false);
            item.put("adjustedPValue", null);
            normalized.add(item);
        }

        List<Map<String, Object>> sorted = new ArrayList<>(normalized);
        sorted.sort(java.util.Comparator.comparingDouble(a -> (Double) a.get("pValue")));
        double previousAdjusted = 0;
        boolean stillSignificant = true;
        for (int i = 0; i < sorted.size(); i++) {
            Map<String, Object> item = sorted.get(i);
            double p = (Double) item.get("pValue");
            int rank = i + 1;
            double adjusted = Math.min(1.0, Math.max(previousAdjusted, p * (sorted.size() - rank + 1)));
            previousAdjusted = adjusted;
            boolean significant = stillSignificant && p <= ALPHA / (sorted.size() - rank + 1);
            if (!significant) {
                stillSignificant = false;
            }
            item.put("adjustedPValue", round(adjusted));
            item.put("significant", significant);
        }

        Map<String, Object> primary = normalized.stream()
                .filter(c -> Objects.equals(c.get("metric"), experiment.getPrimaryMetric()))
                .findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.BAD_REQUEST,
                        "metrics.comparisons 缺少 primaryMetric: " + experiment.getPrimaryMetric()));
        boolean guardrailViolation = normalized.stream().anyMatch(c -> {
            if (!Boolean.TRUE.equals(c.get("guardrail"))) {
                return false;
            }
            double lift = (Double) c.get("relativeLift");
            Double upper = (Double) c.get("ciUpper");
            return lift < 0 || (upper != null && upper < 0);
        });

        checks.put("comparisonMode", "HOLM");
        checks.put("adjustmentMethod", "HOLM");
        checks.put("familySize", normalized.size());
        checks.put("alpha", ALPHA);
        checks.put("comparisons", normalized);
        checks.put("primaryMetric", experiment.getPrimaryMetric());
        checks.put("primaryLift", primary.get("relativeLift"));
        checks.put("primaryAdjustedPValue", primary.get("adjustedPValue"));
        checks.put("guardrailViolation", guardrailViolation);
        checks.put("hasInference", true);
        checks.put("significant", Boolean.TRUE.equals(primary.get("significant")));
        checks.put("relativeLift", primary.get("relativeLift"));
        return normalized;
    }

    private Map<String, Object> qualityChecks(BusinessExperimentEntity experiment,
                                              Map<String, Object> metrics,
                                              Map<String, Object> supplied) {
        Map<String, Object> checks = new LinkedHashMap<>();
        int sampleSize = intValue(first(metrics, "sampleSize", "sample_size", "validUnits"), 0);
        int controlSize = intValue(first(metrics, "controlSize", "control_size"), -1);
        int treatmentSize = intValue(first(metrics, "treatmentSize", "treatment_size"), -1);
        double pValue = doubleValue(first(metrics, "pValue", "p_value"), -1);
        double ciLower = doubleValue(first(metrics, "ciLower", "ci_lower"), Double.NaN);
        double ciUpper = doubleValue(first(metrics, "ciUpper", "ci_upper"), Double.NaN);
        double lift = doubleValue(first(metrics, "relativeLift", "relative_lift",
                "absoluteDiff", "absolute_diff"), 0);
        double srmPValue = doubleValue(first(metrics, "srmPValue", "srm_p_value"), -1);

        // 证据最小契约要求有效样本数；缺失时 fail-closed，不能当作 0 样本通过。
        boolean sampleOk = sampleSize > 0 && sampleSize >= Math.max(0,
                experiment.getMinSampleSize() == null ? 0 : experiment.getMinSampleSize());
        boolean mature = !Boolean.FALSE.equals(first(supplied, "mature"));
        boolean srmOk = !Boolean.FALSE.equals(first(supplied, "srmOk", "srm_ok"))
                && !(srmPValue >= 0 && srmPValue < 0.001);
        boolean guardrailsOk = !Boolean.FALSE.equals(first(supplied, "guardrailsOk", "guardrails_ok"));
        boolean significant = (pValue >= 0 && pValue <= 0.05)
                || (!Double.isNaN(ciLower) && !Double.isNaN(ciUpper)
                    && (ciLower > 0 || ciUpper < 0));
        boolean hasInference = pValue >= 0 || (!Double.isNaN(ciLower) && !Double.isNaN(ciUpper));

        checks.put("sampleSize", sampleSize);
        checks.put("controlSize", controlSize);
        checks.put("treatmentSize", treatmentSize);
        checks.put("minSampleSize", experiment.getMinSampleSize());
        checks.put("sampleOk", sampleOk);
        checks.put("mature", mature);
        checks.put("srmOk", srmOk);
        checks.put("guardrailsOk", guardrailsOk);
        checks.put("pValue", pValue < 0 ? null : pValue);
        checks.put("ciLower", Double.isNaN(ciLower) ? null : ciLower);
        checks.put("ciUpper", Double.isNaN(ciUpper) ? null : ciUpper);
        checks.put("relativeLift", lift);
        checks.put("significant", significant);
        checks.put("hasInference", hasInference);
        checks.put("designType", experiment.getDesignType());
        if (supplied != null) {
            supplied.forEach((k, v) -> checks.putIfAbsent(k, v));
        }
        return checks;
    }

    private String decide(String designType, Map<String, Object> checks) {
        if (!Boolean.TRUE.equals(checks.get("sampleOk"))
                || !Boolean.TRUE.equals(checks.get("mature"))
                || !Boolean.TRUE.equals(checks.get("srmOk"))
                || !Boolean.TRUE.equals(checks.get("guardrailsOk"))) {
            return "BLOCKED";
        }
        if ("HOLM".equals(checks.get("comparisonMode"))) {
            if (Boolean.TRUE.equals(checks.get("guardrailViolation"))) {
                return "BLOCKED";
            }
            if ("OBSERVATIONAL".equals(designType)
                    || !Boolean.TRUE.equals(checks.get("significant"))) {
                return "INCONCLUSIVE";
            }
            double lift = doubleValue(checks.get("relativeLift"), 0);
            return lift > 0 ? "POSITIVE" : lift < 0 ? "NEGATIVE" : "INCONCLUSIVE";
        }
        if ("OBSERVATIONAL".equals(designType)) {
            return "INCONCLUSIVE";
        }
        if (!Boolean.TRUE.equals(checks.get("hasInference"))) {
            return "INCONCLUSIVE";
        }
        if (!Boolean.TRUE.equals(checks.get("significant"))) {
            return "INCONCLUSIVE";
        }
        double lift = doubleValue(checks.get("relativeLift"), 0);
        return lift > 0 ? "POSITIVE" : lift < 0 ? "NEGATIVE" : "INCONCLUSIVE";
    }

    private Map<String, Object> experimentMap(BusinessExperimentEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("source", e.getSource());
        map.put("externalExperimentId", e.getExternalExperimentId());
        map.put("agentKey", e.getAgentKey());
        map.put("runId", e.getRunId());
        map.put("candidateFingerprint", e.getCandidateFingerprint());
        map.put("controlFingerprint", e.getControlFingerprint());
        map.put("target", e.getTarget());
        map.put("designType", e.getDesignType());
        map.put("design", parseMap(e.getDesignJson()));
        map.put("primaryMetric", e.getPrimaryMetric());
        map.put("guardrails", parseList(e.getGuardrailsJson()));
        map.put("minSampleSize", e.getMinSampleSize());
        map.put("requireSignature", e.getRequireSignature());
        map.put("startedAt", e.getStartedAt());
        map.put("endedAt", e.getEndedAt());
        map.put("createdBy", e.getCreatedBy());
        map.put("createdAt", e.getCreatedAt());
        return map;
    }

    private Map<String, Object> evidenceMap(BusinessEvidenceEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("reportVersion", e.getReportVersion());
        map.put("payloadHash", e.getPayloadHash());
        map.put("design", parseMap(e.getDesignJson()));
        map.put("metrics", parseMap(e.getMetricsJson()));
        map.put("qualityChecks", parseMap(e.getQualityChecksJson()));
        map.put("comparisons", parseList(e.getComparisonsJson()));
        map.put("adjustmentMethod", e.getAdjustmentMethod());
        map.put("decision", e.getDecision());
        map.put("reportedAt", e.getReportedAt());
        map.put("receivedBy", e.getReceivedBy());
        map.put("receivedAt", e.getReceivedAt());
        map.put("sourceKeyId", e.getSourceKeyId());
        map.put("signedAt", e.getSignedAt());
        map.put("signatureVerified", e.getSignatureVerified());
        return map;
    }

    private Object first(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private Double optionalDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        return doubleValue(raw, Double.NaN);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private int intValue(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return raw instanceof Number ? ((Number) raw).intValue()
                    : Integer.parseInt(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private double doubleValue(Object raw, double defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return raw instanceof Number ? ((Number) raw).doubleValue()
                    : Double.parseDouble(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String required(String value, String field, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    field + " 超过最大长度 " + maxLength);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evidence payload 无法序列化");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evidence payload 无法序列化");
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
