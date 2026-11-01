package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.api.AutoRubricController.CandidateRule;
import io.emonlens.server.api.AutoRubricController.CreateRequest;
import io.emonlens.server.api.AutoRubricController.DecisionRequest;
import io.emonlens.server.storage.entity.AutoRubricCandidateEntity;
import io.emonlens.server.storage.entity.AutoRubricRunEntity;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.AutoRubricCandidateMapper;
import io.emonlens.server.storage.mapper.AutoRubricRunMapper;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

import lombok.RequiredArgsConstructor;

/**
 * Auto Rubrics 首切片：只做候选规则审计，不生成、不发布规则。
 * 审计集固定 HOLDOUT，标签来自指定已完成 run；人工批准只改候选状态。
 */
@Service
@RequiredArgsConstructor
public class AutoRubricService {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_NO_AUDIT = "NO_AUDIT_SET";
    public static final String STATUS_NO_FAILURES = "NO_FAILURES";
    public static final String CANDIDATE_PROPOSED = "PROPOSED";
    public static final String CANDIDATE_REJECTED = "REJECTED";
    public static final String CANDIDATE_APPROVED = "APPROVED";

    private static final String SEMANTIC_DEDUP_PROMPT =
            "You are a semantic rubric comparator. Given two rubric rules, decide whether they "
            + "express the same requirement. Return strict JSON only: "
            + "{\"duplicate\":true|false,\"reason\":\"...\"}.";

    private static final String SYSTEM_PROMPT = "You are an audit judge. Given one candidate rubric "
            + "and one sample, decide whether the candidate output violates the rubric. "
            + "Return strict JSON only: {\"violation\":true|false,\"reason\":\"...\"}.";
    private static final int MIN_AUDIT_COUNT = 5;
    private static final int MAX_CANDIDATES = 10;
    private static final double NEAR_DUPLICATE_DICE = 0.8;
    private static final String SYSTEM_PROMPT_GENERATOR = "You are an auto-rubric generator. "
            + "Given failed evaluation samples, propose candidate rubric rules that would catch "
            + "the failures on unseen data. Do not repeat existing rules. Return strict JSON "
            + "array only: [{\"key\":\"snake_case_key\",\"text\":\"rule text\"}].";
    private static final String ORIGIN_MANUAL = "MANUAL";
    private static final String ORIGIN_GENERATED = "GENERATED";
    private static final Pattern RULE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{1,63}");

    private final AutoRubricRunMapper runMapper;
    private final AutoRubricCandidateMapper candidateMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRunMapper runMapper2;
    private final DatasetRunItemMapper runItemMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    /**
     * 审计会同步发起最多 400 次 LLM 调用：不能包在 @Transactional 里，
     * 否则会持有数据库连接/事务直到所有评审返回（连接池耗尽、长事务）。
     * 运行状态先落 RUNNING，成功收口 COMPLETED，异常收口 FAILED。
     */
    public Map<String, Object> create(String workspaceId, String datasetId, CreateRequest request) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !Objects.equals(dataset.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        if (request == null || request.getSourceRunId() == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "sourceRunId is required");
        }
        DatasetRunEntity sourceRun = runMapper2.selectById(request.getSourceRunId());
        if (sourceRun == null || !Objects.equals(sourceRun.getWorkspaceId(), workspaceId)
                || !Objects.equals(sourceRun.getDatasetId(), datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "source run not found");
        }
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(sourceRun.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "source run 必须 COMPLETED");
        }
        int maxCalls = request.getMaxJudgeCalls() == null ? 200 : request.getMaxJudgeCalls();
        if (maxCalls < 1 || maxCalls > 400) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "maxJudgeCalls 只允许 1..400");
        }
        String model = request.getJudgeModel() == null || request.getJudgeModel().isBlank()
                ? defaultJudgeModel() : request.getJudgeModel().trim();
        int bootstrapRounds = request.getBootstrapRounds() == null
                ? 200 : request.getBootstrapRounds();
        if (bootstrapRounds < 0 || bootstrapRounds > 1000) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "bootstrapRounds 只允许 0..1000");
        }
        double minSelectionFrequency = request.getMinSelectionFrequency() == null
                ? 0.6 : request.getMinSelectionFrequency();
        if (minSelectionFrequency < 0 || minSelectionFrequency > 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "minSelectionFrequency 必须在 0..1");
        }
        long bootstrapSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        int cvFolds = request.getCvFolds() == null ? 0 : request.getCvFolds();
        if (cvFolds != 0 && (cvFolds < 2 || cvFolds > 5)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "cvFolds 只允许 0 或 2..5");
        }

        Map<String, DatasetRunItemEntity> runItems = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : runItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, sourceRun.getId()))) {
            runItems.put(item.getItemId(), item);
        }
        List<DatasetItemEntity> allItems = datasetItemMapper.selectList(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, datasetId)
                        .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                                .or().isNull(DatasetItemEntity::getStatus))
                        .orderByAsc(DatasetItemEntity::getId));
        List<DatasetItemEntity> auditItems = new ArrayList<>();
        List<DatasetItemEntity> failureItems = new ArrayList<>();
        for (DatasetItemEntity item : allItems) {
            DatasetRunItemEntity runItem = runItems.get(item.getId());
            if (runItem == null) {
                continue;
            }
            if ("HOLDOUT".equals(item.getSplit())
                    && ("PASS".equals(runItem.getLabel()) || "FAIL".equals(runItem.getLabel()))) {
                auditItems.add(item);
            }
            if (!"HOLDOUT".equals(item.getSplit()) && "FAIL".equals(runItem.getLabel())) {
                failureItems.add(item);
            }
        }

        boolean generate = Boolean.TRUE.equals(request.getGenerate());
        int maxGenerated = request.getMaxCandidates() == null ? 3 : request.getMaxCandidates();
        if (maxGenerated < 1 || maxGenerated > MAX_CANDIDATES) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "maxCandidates 只允许 1.." + MAX_CANDIDATES);
        }
        String generationModel = request.getGenerationModel() == null
                || request.getGenerationModel().isBlank()
                ? model : request.getGenerationModel().trim();
        List<CandidateRule> rules = new ArrayList<>();
        if (request.getCandidates() != null && !request.getCandidates().isEmpty()) {
            rules.addAll(normalizeRules(request.getCandidates()));
        }
        boolean semanticDedup = Boolean.TRUE.equals(request.getSemanticDedup());
        int generationCalls = 0;
        if (generate && !failureItems.isEmpty()) {
            List<CandidateRule> generated = generate(failureItems, runItems, maxGenerated,
                    generationModel);
            generationCalls = 1;
            for (CandidateRule candidate : generated) {
                boolean duplicateKey = rules.stream().anyMatch(
                        r -> r.getKey().equals(candidate.getKey()));
                boolean duplicateText = rules.stream().anyMatch(r -> r.getText()
                        .toLowerCase().replaceAll("\\s+", " ")
                        .equals(candidate.getText().toLowerCase().replaceAll("\\s+", " ")));
                boolean nearDuplicate = !duplicateText && rules.stream()
                        .anyMatch(r -> textSimilarity(r.getText(), candidate.getText())
                                >= NEAR_DUPLICATE_DICE);
                boolean semanticDuplicate = !duplicateText && !nearDuplicate && semanticDedup
                        && rules.stream().anyMatch(r -> semanticDuplicate(
                                r.getText(), candidate.getText(), generationModel));
                if (!duplicateKey && !duplicateText && !nearDuplicate && !semanticDuplicate) {
                    rules.add(candidate);
                }
            }
        }
        if (rules.isEmpty() && !generate) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "candidates 不能为空");
        }
        if (rules.isEmpty()) {
            AutoRubricRunEntity empty = new AutoRubricRunEntity();
            empty.setWorkspaceId(workspaceId);
            empty.setDatasetId(datasetId);
            empty.setSourceRunId(sourceRun.getId());
            empty.setStatus(generate && failureItems.isEmpty()
                    ? STATUS_NO_FAILURES : STATUS_COMPLETED);
            empty.setAuditSplit("HOLDOUT");
            empty.setAuditCount(auditItems.size());
            empty.setCandidateCount(0);
            empty.setProposedCount(0);
            empty.setMaxJudgeCalls(maxCalls);
            empty.setJudgeCalls(0);
            empty.setGenerationCalls(0);
            empty.setBootstrapRounds(bootstrapRounds);
            empty.setBootstrapSeed(bootstrapSeed);
            empty.setMinSelectionFrequency(minSelectionFrequency);
            empty.setJudgeModel(model);
            empty.setGenerationModel(generationModel);
            empty.setConfigJson(toJson(Map.of("minAuditCount", MIN_AUDIT_COUNT,
                    "generate", generate, "maxCandidates", maxGenerated)));
            empty.setCreatedBy(request.getCreatedBy());
            empty.setCreatedAt(LocalDateTime.now());
            empty.setFinishedAt(LocalDateTime.now());
            runMapper.insert(empty);
            return detail(workspaceId, empty.getId());
        }
        if (rules.size() > MAX_CANDIDATES) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "候选总数最多 " + MAX_CANDIDATES + " 条");
        }

        AutoRubricRunEntity run = new AutoRubricRunEntity();
        run.setWorkspaceId(workspaceId);
        run.setDatasetId(datasetId);
        run.setSourceRunId(sourceRun.getId());
        run.setStatus(STATUS_RUNNING);
        run.setAuditSplit("HOLDOUT");
        run.setAuditCount(auditItems.size());
        run.setCandidateCount(rules.size());
        run.setProposedCount(0);
        run.setMaxJudgeCalls(maxCalls);
        run.setJudgeCalls(0);
        run.setGenerationCalls(generationCalls);
        run.setBootstrapRounds(bootstrapRounds);
        run.setBootstrapSeed(bootstrapSeed);
        run.setMinSelectionFrequency(minSelectionFrequency);
        run.setJudgeModel(model);
        run.setGenerationModel(generationModel);
        run.setConfigJson(toJson(Map.of("minAuditCount", MIN_AUDIT_COUNT,
                "ruleCount", rules.size(), "generate", generate,
                "maxCandidates", maxGenerated, "bootstrapRounds", bootstrapRounds,
                "minSelectionFrequency", minSelectionFrequency, "cvFolds", cvFolds)));
        run.setCreatedBy(request.getCreatedBy());
        run.setCreatedAt(LocalDateTime.now());
        if (auditItems.size() < MIN_AUDIT_COUNT) {
            run.setStatus(STATUS_NO_AUDIT);
            run.setFinishedAt(LocalDateTime.now());
            runMapper.insert(run);
            return detail(workspaceId, run.getId());
        }
        long plannedCalls = (long) auditItems.size() * rules.size();
        if (plannedCalls > maxCalls) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "审计调用数 " + plannedCalls + " 超过 maxJudgeCalls=" + maxCalls);
        }
        runMapper.insert(run);

        int calls = 0;
        int proposed = 0;
        List<AutoRubricCandidateEntity> candidates = new ArrayList<>();
        try {
            for (CandidateRule rule : rules) {
                AutoRubricCandidateEntity candidate = audit(workspaceId, run, rule, auditItems,
                        runItems, model, bootstrapRounds, minSelectionFrequency, bootstrapSeed);
                candidate.setCreatedAt(LocalDateTime.now());
                candidateMapper.insert(candidate);
                candidates.add(candidate);
                calls += auditItems.size();
                if (CANDIDATE_PROPOSED.equals(candidate.getDecision())) {
                    proposed++;
                }
            }
            if (cvFolds >= 2) {
                run.setSelectionJson(buildSelection(cvFolds, auditItems, runItems, candidates,
                        bootstrapSeed));
            }
            run.setStatus(STATUS_COMPLETED);
            run.setJudgeCalls(calls);
            run.setProposedCount(proposed);
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (RuntimeException e) {
            // 已写入的候选证据保留；运行状态明确收口为 FAILED，便于人工排查/重跑
            run.setStatus(STATUS_FAILED);
            run.setJudgeCalls(calls);
            run.setProposedCount(proposed);
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            throw e;
        }
        return detail(workspaceId, run.getId());
    }

    public Map<String, Object> detail(String workspaceId, String runId) {
        AutoRubricRunEntity run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "auto rubric run not found");
        }
        Map<String, Object> map = runMap(run);
        map.put("candidates", candidateMapper.selectList(
                        Wrappers.<AutoRubricCandidateEntity>lambdaQuery()
                                .eq(AutoRubricCandidateEntity::getRunId, runId)
                                .orderByAsc(AutoRubricCandidateEntity::getCreatedAt))
                .stream().map(this::candidateMap).collect(java.util.stream.Collectors.toList()));
        return map;
    }

    public List<Map<String, Object>> list(String workspaceId, String datasetId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AutoRubricRunEntity run : runMapper.selectList(
                Wrappers.<AutoRubricRunEntity>lambdaQuery()
                        .eq(AutoRubricRunEntity::getWorkspaceId, workspaceId)
                        .eq(AutoRubricRunEntity::getDatasetId, datasetId)
                        .orderByDesc(AutoRubricRunEntity::getCreatedAt))) {
            result.add(runMap(run));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> decide(String workspaceId, String runId, String candidateId,
                                      DecisionRequest request, String reviewer) {
        AutoRubricRunEntity run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "auto rubric run not found");
        }
        AutoRubricCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || !Objects.equals(candidate.getRunId(), runId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "candidate not found");
        }
        String decision = request == null || request.getDecision() == null
                ? "" : request.getDecision().trim().toUpperCase();
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "decision 只允许 APPROVED/REJECTED");
        }
        if (!CANDIDATE_PROPOSED.equals(candidate.getDecision())) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "只有 PROPOSED 候选可人工决策，当前=" + candidate.getDecision());
        }
        candidate.setDecision(decision);
        candidate.setReviewNote(request.getNote());
        candidate.setReviewedBy(reviewer);
        candidate.setReviewedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        return candidateMap(candidate);
    }

    private AutoRubricCandidateEntity audit(String workspaceId, AutoRubricRunEntity run,
                                            CandidateRule rule, List<DatasetItemEntity> auditItems,
                                            Map<String, DatasetRunItemEntity> runItems,
                                            String model, int bootstrapRounds,
                                            double minSelectionFrequency, long bootstrapSeed) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        int violations = 0;
        int errors = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        List<boolean[]> points = new ArrayList<>();
        for (DatasetItemEntity item : auditItems) {
            DatasetRunItemEntity runItem = runItems.get(item.getId());
            boolean shouldFail = "FAIL".equals(runItem.getLabel());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("itemId", item.getId());
            try {
                String user = toJson(Map.of(
                        "rule", rule.getText(),
                        "input", item.getInput() == null ? "" : item.getInput(),
                        "reference", item.getExpectedOutput() == null ? "" : item.getExpectedOutput(),
                        "candidate_output", candidateOutput(runItem) == null
                                ? "" : candidateOutput(runItem)));
                LlmClient.ChatReply reply = llmClient.chatWithUsage(SYSTEM_PROMPT, user, model, 0.0);
                Boolean violation = parseViolation(reply.content());
                if (violation == null) {
                    errors++;
                    detail.put("error", "INVALID_JUDGE_OUTPUT");
                } else {
                    detail.put("violation", violation);
                    detail.put("expectedLabel", shouldFail ? "FAIL" : "PASS");
                    points.add(new boolean[] {violation, shouldFail});
                    if (violation) {
                        violations++;
                        if (shouldFail) {
                            tp++;
                        } else {
                            fp++;
                        }
                    } else if (shouldFail) {
                        fn++;
                    } else {
                        tn++;
                    }
                }
            } catch (Exception e) {
                errors++;
                detail.put("error", e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
            details.add(detail);
        }
        int usable = tp + fp + fn + tn;
        double precision = tp + fp == 0 ? 0.0 : round(tp * 100.0 / (tp + fp));
        double recall = tp + fn == 0 ? 0.0 : round(tp * 100.0 / (tp + fn));
        int support = tp + fp;
        boolean baseProposed = errors == 0 && usable >= MIN_AUDIT_COUNT
                && precision >= rule.getMinPrecision() * 100.0
                && support >= rule.getMinSupport();
        double selectionFrequency = selectionFrequency(points, rule, bootstrapRounds,
                bootstrapSeed + rule.getKey().hashCode());
        boolean stable = bootstrapRounds == 0
                || selectionFrequency >= minSelectionFrequency * 100.0;
        boolean proposed = baseProposed && stable;

        AutoRubricCandidateEntity entity = new AutoRubricCandidateEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRunId(run.getId());
        entity.setRuleKey(rule.getKey());
        entity.setRuleText(rule.getText());
        entity.setOrigin(rule.getOrigin() == null ? ORIGIN_MANUAL : rule.getOrigin());
        entity.setDecision(proposed ? CANDIDATE_PROPOSED : CANDIDATE_REJECTED);
        entity.setAuditCount(usable);
        entity.setViolationCount(violations);
        entity.setTruePositive(tp);
        entity.setFalsePositive(fp);
        entity.setFalseNegative(fn);
        entity.setTrueNegative(tn);
        entity.setPrecisionRate(precision);
        entity.setRecall(recall);
        entity.setSupportCount(support);
        entity.setSelectionFrequency(selectionFrequency);
        entity.setStable(stable);
        entity.setViolationRate(usable == 0 ? 0.0 : round(violations * 100.0 / usable));
        entity.setMinPrecision(rule.getMinPrecision());
        entity.setMinSupport(rule.getMinSupport());
        entity.setDetailsJson(toJson(Map.of("errors", errors, "items", details)));
        return entity;
    }

    private double selectionFrequency(List<boolean[]> points, CandidateRule rule,
                                      int rounds, long seed) {
        if (rounds <= 0 || points.isEmpty()) {
            return 100.0;
        }
        java.util.Random random = new java.util.Random(seed);
        int selected = 0;
        for (int r = 0; r < rounds; r++) {
            int tp = 0;
            int fp = 0;
            for (int i = 0; i < points.size(); i++) {
                boolean[] point = points.get(random.nextInt(points.size()));
                if (point[0]) {
                    if (point[1]) {
                        tp++;
                    } else {
                        fp++;
                    }
                }
            }
            int support = tp + fp;
            double precision = support == 0 ? 0.0 : tp * 100.0 / support;
            if (support >= rule.getMinSupport()
                    && precision >= rule.getMinPrecision() * 100.0) {
                selected++;
            }
        }
        return round(selected * 100.0 / rounds);
    }

    /** 未显式指定模型时用系统当前生效的 LLM model，避免硬编码模型与部署配置不一致。 */
    private String defaultJudgeModel() {
        SysSettingService.EvalLlm cfg = llmClient.effectiveConfig();
        return cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                ? cfg.getModel() : "gpt-4o-mini";
    }

    /** TRACE 模式 run 的 replay_output 为空：回退到 resultDetails.candidateOutput（与成对比较同口径）。 */
    private String candidateOutput(DatasetRunItemEntity item) {
        if (item == null) {
            return null;
        }
        if (item.getReplayOutput() != null && !item.getReplayOutput().isBlank()) {
            return item.getReplayOutput();
        }
        Map<String, Object> details = parseMap(item.getResultDetailsJson());
        Object output = details == null ? null : details.get("candidateOutput");
        return output == null || String.valueOf(output).isBlank() ? null : String.valueOf(output);
    }

    private List<CandidateRule> generate(List<DatasetItemEntity> failures,
                                         Map<String, DatasetRunItemEntity> runItems,
                                         int maxCandidates, String model) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (DatasetItemEntity item : failures.stream().limit(5)
                .collect(java.util.stream.Collectors.toList())) {
            DatasetRunItemEntity runItem = runItems.get(item.getId());
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("input", truncate(item.getInput(), 2000));
            sample.put("reference", truncate(item.getExpectedOutput(), 2000));
            sample.put("candidate_output", truncate(candidateOutput(runItem), 2000));
            sample.put("reason", truncate(runItem.getReason(), 500));
            samples.add(sample);
        }
        String user;
        try {
            user = objectMapper.writeValueAsString(Map.of(
                    "maxCandidates", maxCandidates, "failedSamples", samples));
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "候选生成请求无法序列化");
        }
        String content;
        try {
            content = llmClient.chatWithUsage(SYSTEM_PROMPT_GENERATOR, user, model, 0.2).content();
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "候选生成调用失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
        }
        int start = content == null ? -1 : content.indexOf('[');
        int end = content == null ? -1 : content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "候选生成输出不是 JSON 数组");
        }
        List<Map<String, Object>> rows;
        try {
            rows = objectMapper.readValue(content.substring(start, end + 1),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "候选生成输出不是合法 JSON");
        }
        List<CandidateRule> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("key") == null ? null : String.valueOf(row.get("key")).trim();
            String text = row.get("text") == null ? null : String.valueOf(row.get("text")).trim();
            if (key == null || text == null || key.isEmpty() || text.isEmpty()
                    || !RULE_KEY.matcher(key).matches() || text.length() > 2000) {
                continue;
            }
            String normalized = text.toLowerCase().replaceAll("\\s+", " ");
            if (!keys.add(key) || !texts.add(normalized)) {
                continue;
            }
            CandidateRule rule = new CandidateRule();
            rule.setKey(key);
            rule.setText(text);
            rule.setMinPrecision(0.8);
            rule.setMinSupport(3);
            rule.setOrigin(ORIGIN_GENERATED);
            result.add(rule);
            if (result.size() >= maxCandidates) {
                break;
            }
        }
        return result;
    }

    private List<CandidateRule> normalizeRules(List<CandidateRule> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "candidates 不能为空");
        }
        if (raw.size() > MAX_CANDIDATES) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "candidates 最多 " + MAX_CANDIDATES + " 条");
        }
        List<CandidateRule> rules = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (CandidateRule rule : raw) {
            if (rule == null || trimToNull(rule.getKey()) == null
                    || !RULE_KEY.matcher(rule.getKey().trim()).matches()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.key 必须匹配 [A-Za-z][A-Za-z0-9_-]{1,63}");
            }
            String text = trimToNull(rule.getText());
            if (text == null || text.length() > 2000) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.text 不能为空且不超过 2000 字");
            }
            double minPrecision = rule.getMinPrecision() == null ? 0.8 : rule.getMinPrecision();
            if (minPrecision < 0 || minPrecision > 1) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.minPrecision 必须在 0..1");
            }
            int minSupport = rule.getMinSupport() == null ? 3 : rule.getMinSupport();
            if (minSupport < 1 || minSupport > 100) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.minSupport 只允许 1..100");
            }
            if (!keys.add(rule.getKey().trim())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.key 重复: " + rule.getKey());
            }
            String normalized = text.toLowerCase().replaceAll("\\s+", " ");
            if (!texts.add(normalized)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidate.text 重复（忽略空白与大小写）");
            }
            CandidateRule normalizedRule = new CandidateRule();
            normalizedRule.setKey(rule.getKey().trim());
            normalizedRule.setText(text);
            normalizedRule.setMinPrecision(minPrecision);
            normalizedRule.setMinSupport(minSupport);
            normalizedRule.setOrigin(rule.getOrigin() == null ? ORIGIN_MANUAL : rule.getOrigin());
            rules.add(normalizedRule);
        }
        return rules;
    }

    private Boolean parseViolation(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(content.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {});
            Object raw = map.get("violation");
            if (raw instanceof Boolean) {
                return (Boolean) raw;
            }
            if (raw != null) {
                String text = String.valueOf(raw).trim().toLowerCase();
                if ("true".equals(text)) {
                    return true;
                }
                if ("false".equals(text)) {
                    return false;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private String buildSelection(int folds, List<DatasetItemEntity> auditItems,
                                  Map<String, DatasetRunItemEntity> runItems,
                                  List<AutoRubricCandidateEntity> candidates, long seed) {
        List<SelectionCandidate> predictions = new ArrayList<>();
        Set<String> allIds = new HashSet<>();
        Map<String, Boolean> labels = new LinkedHashMap<>();
        for (DatasetItemEntity item : auditItems) {
            DatasetRunItemEntity runItem = runItems.get(item.getId());
            if (runItem == null || runItem.getLabel() == null) {
                continue;
            }
            allIds.add(item.getId());
            labels.put(item.getId(), "FAIL".equals(runItem.getLabel()));
        }
        for (AutoRubricCandidateEntity candidate : candidates) {
            Map<String, Object> details = parseMap(candidate.getDetailsJson());
            Object itemsRaw = details == null ? null : details.get("items");
            if (!(itemsRaw instanceof List)) {
                continue;
            }
            Map<String, Boolean> byItem = new LinkedHashMap<>();
            for (Object row : (List<?>) itemsRaw) {
                if (row instanceof Map && ((Map<?, ?>) row).get("itemId") != null) {
                    Map<?, ?> item = (Map<?, ?>) row;
                    if (item.get("violation") instanceof Boolean) {
                        byItem.put(String.valueOf(item.get("itemId")),
                                (Boolean) item.get("violation"));
                    }
                }
            }
            predictions.add(new SelectionCandidate(candidate.getRuleKey(), byItem));
        }
        predictions.sort(Comparator.comparing(c -> c.key));
        List<String> selected = greedySelect(allIds, predictions, labels);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folds", folds);
        result.put("selectedKeys", selected);
        result.put("fullAudit", metrics(allIds, selected, predictions, labels));
        List<String> orderedIds = new ArrayList<>(allIds);
        orderedIds.sort(Comparator.comparingInt(id -> (id + ":" + seed).hashCode()));
        Map<String, Integer> foldOf = new LinkedHashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            foldOf.put(orderedIds.get(i), i % folds);
        }
        List<Map<String, Object>> foldResults = new ArrayList<>();
        double sumF1 = 0;
        double sumPrecision = 0;
        double sumRecall = 0;
        int counted = 0;
        for (int fold = 0; fold < folds; fold++) {
            Set<String> train = new HashSet<>();
            Set<String> test = new HashSet<>();
            for (String id : orderedIds) {
                if (foldOf.get(id) == fold) {
                    test.add(id);
                } else {
                    train.add(id);
                }
            }
            if (train.isEmpty() || test.isEmpty()) {
                continue;
            }
            List<String> foldSelected = greedySelect(train, predictions, labels);
            Map<String, Object> foldMetrics = metrics(test, foldSelected, predictions, labels);
            foldMetrics.put("fold", fold);
            foldResults.add(foldMetrics);
            sumF1 += ((Number) foldMetrics.get("f1")).doubleValue();
            sumPrecision += ((Number) foldMetrics.get("precision")).doubleValue();
            sumRecall += ((Number) foldMetrics.get("recall")).doubleValue();
            counted++;
        }
        Map<String, Object> cv = new LinkedHashMap<>();
        cv.put("folds", counted);
        cv.put("meanF1", counted == 0 ? 0.0 : round4(sumF1 / counted));
        cv.put("meanPrecision", counted == 0 ? 0.0 : round4(sumPrecision / counted));
        cv.put("meanRecall", counted == 0 ? 0.0 : round4(sumRecall / counted));
        cv.put("foldResults", foldResults);
        result.put("cv", cv);
        return toJson(result);
    }

    private List<String> greedySelect(Set<String> itemIds,
                                      List<SelectionCandidate> predictions,
                                      Map<String, Boolean> labels) {
        List<String> selected = new ArrayList<>();
        double best = metrics(itemIds, selected, predictions, labels).get("f1") instanceof Number
                ? ((Number) metrics(itemIds, selected, predictions, labels).get("f1")).doubleValue()
                : 0.0;
        while (selected.size() < 5) {
            String winner = null;
            double winnerF1 = best;
            for (SelectionCandidate candidate : predictions) {
                if (selected.contains(candidate.key)) {
                    continue;
                }
                List<String> trial = new ArrayList<>(selected);
                trial.add(candidate.key);
                double f1 = ((Number) metrics(itemIds, trial, predictions, labels).get("f1"))
                        .doubleValue();
                if (f1 > winnerF1 + 1e-9) {
                    winnerF1 = f1;
                    winner = candidate.key;
                }
            }
            if (winner == null) {
                break;
            }
            selected.add(winner);
            best = winnerF1;
        }
        return selected;
    }

    private Map<String, Object> metrics(Set<String> itemIds, List<String> selected,
                                        List<SelectionCandidate> predictions,
                                        Map<String, Boolean> labels) {
        Set<String> keys = new HashSet<>(selected);
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (String id : itemIds) {
            boolean predicted = false;
            for (SelectionCandidate candidate : predictions) {
                if (keys.contains(candidate.key) && candidate.byItem.getOrDefault(id, false)) {
                    predicted = true;
                    break;
                }
            }
            boolean actual = labels.getOrDefault(id, false);
            if (predicted && actual) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (actual) {
                fn++;
            }
        }
        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0 ? 0.0
                : 2 * precision * recall / (precision + recall);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("predicted", tp + fp);
        map.put("positives", tp + fn);
        map.put("tp", tp);
        map.put("fp", fp);
        map.put("fn", fn);
        map.put("precision", round4(precision));
        map.put("recall", round4(recall));
        map.put("f1", round4(f1));
        return map;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private static class SelectionCandidate {
        private final String key;
        private final Map<String, Boolean> byItem;

        private SelectionCandidate(String key, Map<String, Boolean> byItem) {
            this.key = key;
            this.byItem = byItem;
        }
    }

    private Map<String, Object> runMap(AutoRubricRunEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("datasetId", e.getDatasetId());
        map.put("sourceRunId", e.getSourceRunId());
        map.put("status", e.getStatus());
        map.put("auditSplit", e.getAuditSplit());
        map.put("auditCount", e.getAuditCount());
        map.put("candidateCount", e.getCandidateCount());
        map.put("proposedCount", e.getProposedCount());
        map.put("judgeCalls", e.getJudgeCalls());
        map.put("generationCalls", e.getGenerationCalls());
        map.put("bootstrapRounds", e.getBootstrapRounds());
        map.put("bootstrapSeed", e.getBootstrapSeed());
        map.put("minSelectionFrequency", e.getMinSelectionFrequency());
        map.put("maxJudgeCalls", e.getMaxJudgeCalls());
        map.put("judgeModel", e.getJudgeModel());
        map.put("generationModel", e.getGenerationModel());
        map.put("createdBy", e.getCreatedBy());
        map.put("createdAt", e.getCreatedAt());
        map.put("finishedAt", e.getFinishedAt());
        map.put("selection", parseMap(e.getSelectionJson()));
        return map;
    }

    private Map<String, Object> candidateMap(AutoRubricCandidateEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("ruleKey", e.getRuleKey());
        map.put("ruleText", e.getRuleText());
        map.put("origin", e.getOrigin());
        map.put("decision", e.getDecision());
        map.put("auditCount", e.getAuditCount());
        map.put("violationCount", e.getViolationCount());
        map.put("truePositive", e.getTruePositive());
        map.put("falsePositive", e.getFalsePositive());
        map.put("falseNegative", e.getFalseNegative());
        map.put("trueNegative", e.getTrueNegative());
        map.put("precision", e.getPrecisionRate());
        map.put("recall", e.getRecall());
        map.put("support", e.getSupportCount());
        map.put("selectionFrequency", e.getSelectionFrequency());
        map.put("stable", e.getStable());
        map.put("violationRate", e.getViolationRate());
        map.put("minPrecision", e.getMinPrecision());
        map.put("minSupport", e.getMinSupport());
        map.put("reviewNote", e.getReviewNote());
        map.put("reviewedBy", e.getReviewedBy());
        map.put("reviewedAt", e.getReviewedAt());
        map.put("createdAt", e.getCreatedAt());
        return map;
    }

    private boolean semanticDuplicate(String left, String right, String model) {
        try {
            String user = toJson(Map.of("rule_a", left, "rule_b", right));
            String reply = llmClient.chatWithUsage(SEMANTIC_DEDUP_PROMPT, user, model, 0.0).content();
            Map<String, Object> parsed = parseMap(reply);
            return parsed != null && Boolean.TRUE.equals(parsed.get("duplicate"));
        } catch (RuntimeException e) {
            // 语义去重失败不阻断生成；宁可保留候选，交由审计/人工判断。
            return false;
        }
    }

    private double textSimilarity(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return 2.0 * intersection.size() / (a.size() + b.size());
    }

    private Set<String> bigrams(String value) {
        String normalized = value == null ? "" : value.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]", "");
        Set<String> grams = new HashSet<>();
        if (normalized.length() <= 1) {
            if (!normalized.isEmpty()) {
                grams.add(normalized);
            }
            return grams;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "auto rubric payload 无法序列化");
        }
    }
}
