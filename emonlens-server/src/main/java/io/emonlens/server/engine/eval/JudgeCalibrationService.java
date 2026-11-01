package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.JudgeReviewEntity;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;

/**
 * LLM Judge 人工校准。
 *
 * <p>SINGLE：单人盲审（历史兼容）；DOUBLE_BLIND：每条样本两名不同评审独立提交，
 * 分歧必须由第三名评审仲裁后才算完成。全部样本完成前不返回 judge 标签。</p>
 */
@Service
public class JudgeCalibrationService {

    public static final int MIN_REVIEWED = 10;
    public static final double MIN_AGREEMENT = 80.0;
    public static final String STATUS_NOT_REVIEWED = "NOT_REVIEWED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_INSUFFICIENT_SAMPLE = "INSUFFICIENT_SAMPLE";
    public static final String STATUS_LOW_AGREEMENT = "LOW_AGREEMENT";
    public static final String STATUS_OK = "OK";

    public static final String MODE_SINGLE = "SINGLE";
    public static final String MODE_DOUBLE_BLIND = "DOUBLE_BLIND";
    public static final String SLOT_PRIMARY = "PRIMARY";
    public static final String SLOT_SECOND = "SECOND";
    public static final String SLOT_ADJUDICATOR = "ADJUDICATOR";
    /** 当前写入的校准协议版本；历史行默认 judge-calibration/1。 */
    public static final String PROTOCOL_VERSION = "judge-calibration/2";

    @Resource
    private DatasetRunService datasetRunService;
    @Resource
    private JudgeReviewMapper judgeReviewMapper;
    @Resource
    private ObjectMapper objectMapper;

    public Map<String, Object> summary(String workspaceId, String datasetId, String runId,
                                       String evaluatorKey, boolean contentAllowed) {
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, datasetId, runId);
        String reviewMode = reviewMode(run);
        boolean doubleBlind = MODE_DOUBLE_BLIND.equals(reviewMode);
        String currentUser = currentUserIdOrNull();
        List<DatasetRunItemEntity> items = datasetRunService.itemsOf(runId);
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Map<String, Object>> judgeByItem = new LinkedHashMap<>();
        Map<String, String> humanByItem = new LinkedHashMap<>();
        Set<String> reviewableItems = new LinkedHashSet<>();
        String resolvedKey = evaluatorKey;
        Integer resolvedVersion = null;
        int pendingAdjudication = 0;
        Set<String> protocols = new LinkedHashSet<>();
        Map<String, Object> judgeTemplate = null;
        String judgeMode = null;

        for (DatasetRunItemEntity item : items) {
            Map<String, Object> result = judgeResult(item, resolvedKey);
            if (result == null) {
                continue;
            }
            if (judgeTemplate == null && result.get("judgeTemplate") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> template = (Map<String, Object>) result.get("judgeTemplate");
                judgeTemplate = template;
            }
            if (judgeMode == null && result.get("judgeMode") != null) {
                judgeMode = asString(result.get("judgeMode"));
            }
            if (resolvedKey == null) {
                resolvedKey = asString(result.get("evaluatorKey"));
            }
            resolvedVersion = asInt(result.get("evaluatorVersion"));
            String judgeLabel = asString(result.get("label"));
            boolean reviewable = EvaluatorEntity.LABEL_PASS.equals(judgeLabel)
                    || EvaluatorEntity.LABEL_FAIL.equals(judgeLabel);
            List<JudgeReviewEntity> reviews = reviewsOf(runId, item.getItemId(), resolvedKey, resolvedVersion);
            JudgeReviewEntity primary = slot(reviews, SLOT_PRIMARY);
            JudgeReviewEntity second = slot(reviews, SLOT_SECOND);
            JudgeReviewEntity adjudicator = slot(reviews, SLOT_ADJUDICATOR);
            reviews.stream().map(JudgeReviewEntity::getProtocolVersion)
                    .filter(Objects::nonNull).forEach(protocols::add);
            JudgeReviewEntity mine = reviews.stream()
                    .filter(r -> Objects.equals(r.getReviewedBy(), currentUser))
                    .findFirst().orElse(null);

            boolean twoDone = primary != null && second != null;
            boolean reviewersAgree = twoDone
                    && Objects.equals(primary.getHumanLabel(), second.getHumanLabel());
            boolean needsAdjudication = doubleBlind && twoDone && !reviewersAgree && adjudicator == null;
            boolean resolved = doubleBlind
                    ? twoDone && (reviewersAgree || adjudicator != null)
                    : primary != null;
            if (needsAdjudication) {
                pendingAdjudication++;
            }
            boolean canAdjudicate = needsAdjudication
                    && !Objects.equals(currentUser, primary.getReviewedBy())
                    && !Objects.equals(currentUser, second.getReviewedBy());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemId", item.getItemId());
            entry.put("judgeLabel", null);
            entry.put("judgeScore", null);
            entry.put("reasonCode", null);
            entry.put("judgeReason", null);
            entry.put("reviewable", reviewable);
            entry.put("reviewed", resolved);
            entry.put("myReviewed", mine != null);
            entry.put("myLabel", mine == null ? null : mine.getHumanLabel());
            entry.put("reviewCount", reviews.size());
            entry.put("requiredReviews", doubleBlind ? 2 : 1);
            entry.put("needsAdjudication", needsAdjudication);
            entry.put("canAdjudicate", canAdjudicate);
            // 盲审：未全部完成前只显示“当前登录人自己”的判定，绝不泄露其他评审的标签。
            entry.put("humanLabel", resolved ? finalLabel(primary, second, adjudicator) : null);
            entry.put("note", resolved ? finalNote(primary, second, adjudicator)
                    : (mine == null ? null : mine.getNote()));
            entry.put("reviewedBy", resolved ? finalReviewer(primary, second, adjudicator)
                    : (mine == null ? null : mine.getReviewedBy()));
            entry.put("reviewedAt", resolved ? finalReviewedAt(primary, second, adjudicator)
                    : (mine == null ? null : mine.getUpdatedAt()));
            entry.put("agreement", null);
            if (contentAllowed) {
                Map<String, Object> snapshot = parseMap(item.getSampleSnapshotJson());
                Map<String, Object> details = parseMap(item.getResultDetailsJson());
                entry.put("input", snapshot == null ? null : snapshot.get("input"));
                entry.put("expectedOutput", snapshot == null ? null : snapshot.get("expectedOutput"));
                entry.put("candidateOutput", details == null ? null : details.get("candidateOutput"));
            }
            entries.add(entry);
            judgeByItem.put(item.getItemId(), result);
            if (reviewable) {
                reviewableItems.add(item.getItemId());
                if (resolved) {
                    humanByItem.put(item.getItemId(), asString(entry.get("humanLabel")));
                }
            }
        }
        if (entries.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "该 run 没有指定 LLM Judge 的样本");
        }

        int reviewable = reviewableItems.size();
        int reviewed = humanByItem.size();
        boolean revealed = reviewable > 0 && reviewed == reviewable;
        int agreements = 0;
        int judgePass = 0, judgeFail = 0, humanPass = 0, humanFail = 0;
        int tp = 0, fp = 0, tn = 0, fn = 0;
        if (revealed) {
            for (Map<String, Object> entry : entries) {
                String itemId = asString(entry.get("itemId"));
                if (!reviewableItems.contains(itemId)) {
                    continue;
                }
                String judge = asString(judgeByItem.get(itemId).get("label"));
                String human = humanByItem.get(itemId);
                if (judge.equals(human)) {
                    agreements++;
                }
                boolean judgePositive = EvaluatorEntity.LABEL_PASS.equals(judge);
                boolean humanPositive = EvaluatorEntity.LABEL_PASS.equals(human);
                if (judgePositive && humanPositive) {
                    tp++;
                } else if (judgePositive) {
                    fp++;
                } else if (humanPositive) {
                    fn++;
                } else {
                    tn++;
                }
                if (judgePositive) judgePass++; else judgeFail++;
                if (humanPositive) humanPass++; else humanFail++;
                entry.put("judgeLabel", judge);
                entry.put("judgeScore", judgeByItem.get(itemId).get("score"));
                entry.put("reasonCode", judgeByItem.get(itemId).get("reasonCode"));
                entry.put("agreement", judge.equals(human));
                if (contentAllowed) {
                    entry.put("judgeReason", judgeByItem.get(itemId).get("reason"));
                }
                List<JudgeReviewEntity> reviews = reviewsOf(runId, itemId, resolvedKey, resolvedVersion);
                entry.put("primaryLabel", labelOf(slot(reviews, SLOT_PRIMARY)));
                entry.put("secondLabel", labelOf(slot(reviews, SLOT_SECOND)));
                entry.put("adjudicatedLabel", labelOf(slot(reviews, SLOT_ADJUDICATOR)));
                entry.put("reviewers", reviews.stream().map(r -> {
                    Map<String, Object> reviewer = new LinkedHashMap<>();
                    reviewer.put("slot", r.getReviewerSlot());
                    reviewer.put("reviewedBy", r.getReviewedBy());
                    reviewer.put("humanLabel", r.getHumanLabel());
                    reviewer.put("reviewedAt", r.getUpdatedAt());
                    return reviewer;
                }).collect(java.util.stream.Collectors.toList()));
            }
        }
        double agreementRate = revealed ? round(agreements * 100.0 / reviewed) : 0;
        Double kappa = revealed
                ? cohenKappa(reviewed, agreements, judgePass, judgeFail, humanPass, humanFail) : null;
        String status;
        if (reviewed == 0) {
            status = STATUS_NOT_REVIEWED;
        } else if (!revealed) {
            status = STATUS_IN_PROGRESS;
        } else if (reviewed < MIN_REVIEWED) {
            status = STATUS_INSUFFICIENT_SAMPLE;
        } else if (agreementRate < MIN_AGREEMENT) {
            status = STATUS_LOW_AGREEMENT;
        } else {
            status = STATUS_OK;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("reviewMode", reviewMode);
        summary.put("evaluatorKey", resolvedKey);
        summary.put("evaluatorVersion", resolvedVersion);
        summary.put("reviewable", reviewable);
        summary.put("reviewed", reviewed);
        summary.put("pendingAdjudication", pendingAdjudication);
        summary.put("revealed", revealed);
        summary.put("agreements", revealed ? agreements : null);
        summary.put("disagreements", revealed ? reviewed - agreements : null);
        summary.put("agreementRate", revealed ? agreementRate : null);
        summary.put("kappa", kappa);
        summary.put("protocolVersion", protocols.size() == 1 ? protocols.iterator().next() : null);
        summary.put("protocols", new ArrayList<>(protocols));
        summary.put("protocolMixed", protocols.size() > 1);
        summary.put("judgeTemplate", judgeTemplate);
        summary.put("judgeMode", judgeMode);
        summary.put("confusionMatrix", confusionMatrix(revealed, reviewed, tp, fp, tn, fn));
        summary.put("minimumReviewed", MIN_REVIEWED);
        summary.put("minimumAgreement", MIN_AGREEMENT);
        summary.put("status", status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("items", entries);
        return result;
    }

    /** 服务端校准导出：仅全部审完后可用，避免审一半导出泄露 judge 并固化偏差。 */
    public String exportCsv(String workspaceId, String datasetId, String runId,
                            String evaluatorKey, boolean contentAllowed) {
        Map<String, Object> result = summary(workspaceId, datasetId, runId, evaluatorKey, contentAllowed);
        Map<?, ?> summary = (Map<?, ?>) result.get("summary");
        if (!Boolean.TRUE.equals(summary.get("revealed"))) {
            throw new EmonLensException(ErrorCode.CONFLICT, "全部样本审完后才能导出校准 CSV");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("items");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("evaluatorKey,itemId,judgeLabel,humanLabel,agreement,reviewedBy,reviewedAt,note");
        if (contentAllowed) {
            csv.append(",judgeReason,input,candidateOutput");
        }
        csv.append(",primaryLabel,secondLabel,adjudicatedLabel\n");
        for (Map<String, Object> entry : entries) {
            if (!Boolean.TRUE.equals(entry.get("reviewed"))) {
                continue;
            }
            csv.append(csvCell(summary.get("evaluatorKey"))).append(',')
                    .append(csvCell(entry.get("itemId"))).append(',')
                    .append(csvCell(entry.get("judgeLabel"))).append(',')
                    .append(csvCell(entry.get("humanLabel"))).append(',')
                    .append(csvCell(entry.get("agreement") == null ? null
                            : (Boolean.TRUE.equals(entry.get("agreement")) ? "YES" : "NO"))).append(',')
                    .append(csvCell(entry.get("reviewedBy"))).append(',')
                    .append(csvCell(entry.get("reviewedAt"))).append(',')
                    .append(csvCell(entry.get("note")));
            if (contentAllowed) {
                csv.append(',').append(csvCell(entry.get("judgeReason")))
                        .append(',').append(csvCell(entry.get("input")))
                        .append(',').append(csvCell(entry.get("candidateOutput")));
            }
            csv.append(',').append(csvCell(entry.get("primaryLabel")))
                    .append(',').append(csvCell(entry.get("secondLabel")))
                    .append(',').append(csvCell(entry.get("adjudicatedLabel")));
            csv.append('\n');
        }
        return csv.toString();
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf('"') >= 0 || text.indexOf(',') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @Transactional
    public Map<String, Object> review(String workspaceId, String datasetId, String runId, String itemId,
                                      String evaluatorKey, String humanLabel, String note) {
        if (!EvaluatorEntity.LABEL_PASS.equals(humanLabel) && !EvaluatorEntity.LABEL_FAIL.equals(humanLabel)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "humanLabel must be PASS or FAIL");
        }
        if (note != null && note.length() > 500) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "note 不能超过 500 字符");
        }
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, datasetId, runId);
        boolean doubleBlind = MODE_DOUBLE_BLIND.equals(reviewMode(run));
        DatasetRunItemEntity item = datasetRunService.itemsOf(runId).stream()
                .filter(i -> Objects.equals(i.getItemId(), itemId)).findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND, "run item not found"));
        Map<String, Object> judge = judgeResult(item, evaluatorKey);
        if (judge == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "该样本没有指定 LLM Judge 结果");
        }
        String judgeLabel = asString(judge.get("label"));
        if (!EvaluatorEntity.LABEL_PASS.equals(judgeLabel) && !EvaluatorEntity.LABEL_FAIL.equals(judgeLabel)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "judge label 不是 PASS/FAIL，不能校准");
        }
        String key = asString(judge.get("evaluatorKey"));
        Integer version = asInt(judge.get("evaluatorVersion"));
        String reviewer = CurrentContext.require().getUserId();
        List<JudgeReviewEntity> reviews = reviewsOf(runId, itemId, key, version);
        if (reviews.stream().anyMatch(r -> Objects.equals(r.getReviewedBy(), reviewer))) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "你已提交该样本的人工判定，不可修改");
        }
        JudgeReviewEntity primary = slot(reviews, SLOT_PRIMARY);
        JudgeReviewEntity second = slot(reviews, SLOT_SECOND);
        String reviewerSlot;
        if (doubleBlind) {
            if (primary == null) {
                reviewerSlot = SLOT_PRIMARY;
            } else if (second == null) {
                reviewerSlot = SLOT_SECOND;
            } else {
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "该样本两名评审均已提交；分歧请走仲裁，不可继续追加评审");
            }
        } else {
            if (primary != null) {
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "该样本的人工判定已提交，不可修改；如需复核请新建 run");
            }
            reviewerSlot = SLOT_PRIMARY;
        }
        LocalDateTime now = LocalDateTime.now();
        JudgeReviewEntity saved = new JudgeReviewEntity();
        saved.setWorkspaceId(workspaceId);
        saved.setRunId(runId);
        saved.setItemId(itemId);
        saved.setEvaluatorKey(key);
        saved.setEvaluatorVersion(version);
        saved.setJudgeLabel(judgeLabel);
        saved.setHumanLabel(humanLabel);
        saved.setReviewerSlot(reviewerSlot);
        saved.setProtocolVersion(PROTOCOL_VERSION);
        saved.setNote(note);
        saved.setReviewedBy(reviewer);
        saved.setCreatedAt(now);
        saved.setUpdatedAt(now);
        try {
            judgeReviewMapper.insert(saved);
        } catch (DuplicateKeyException e) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "该样本已由其他人提交相同评审槽位，不可覆盖");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", itemId);
        result.put("evaluatorKey", key);
        result.put("evaluatorVersion", version);
        result.put("humanLabel", humanLabel);
        result.put("reviewerSlot", reviewerSlot);
        result.put("note", note);
        result.put("reviewedBy", reviewer);
        result.put("reviewedAt", now);
        result.put("needsAdjudication", doubleBlind && SLOT_SECOND.equals(reviewerSlot)
                && !Objects.equals(primary.getHumanLabel(), humanLabel));
        return result;
    }

    /** 双人盲审分歧的第三方仲裁；仲裁结论只追加，不改写两名原评审记录。 */
    @Transactional
    public Map<String, Object> adjudicate(String workspaceId, String datasetId, String runId, String itemId,
                                          String evaluatorKey, String humanLabel, String note) {
        if (!EvaluatorEntity.LABEL_PASS.equals(humanLabel) && !EvaluatorEntity.LABEL_FAIL.equals(humanLabel)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "humanLabel must be PASS or FAIL");
        }
        if (note != null && note.length() > 500) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "note 不能超过 500 字符");
        }
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, datasetId, runId);
        if (!MODE_DOUBLE_BLIND.equals(reviewMode(run))) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "只有 DOUBLE_BLIND run 需要仲裁");
        }
        DatasetRunItemEntity item = datasetRunService.itemsOf(runId).stream()
                .filter(i -> Objects.equals(i.getItemId(), itemId)).findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND, "run item not found"));
        Map<String, Object> judge = judgeResult(item, evaluatorKey);
        if (judge == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "该样本没有指定 LLM Judge 结果");
        }
        String key = asString(judge.get("evaluatorKey"));
        Integer version = asInt(judge.get("evaluatorVersion"));
        List<JudgeReviewEntity> reviews = reviewsOf(runId, itemId, key, version);
        JudgeReviewEntity primary = slot(reviews, SLOT_PRIMARY);
        JudgeReviewEntity second = slot(reviews, SLOT_SECOND);
        JudgeReviewEntity existing = slot(reviews, SLOT_ADJUDICATOR);
        if (primary == null || second == null) {
            throw new EmonLensException(ErrorCode.CONFLICT, "两名评审都提交后才能仲裁");
        }
        if (Objects.equals(primary.getHumanLabel(), second.getHumanLabel())) {
            throw new EmonLensException(ErrorCode.CONFLICT, "两名评审结论一致，无需仲裁");
        }
        if (existing != null) {
            throw new EmonLensException(ErrorCode.CONFLICT, "该样本已仲裁，不可修改");
        }
        String reviewer = CurrentContext.require().getUserId();
        if (Objects.equals(reviewer, primary.getReviewedBy())
                || Objects.equals(reviewer, second.getReviewedBy())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "仲裁人不能是原评审人");
        }
        LocalDateTime now = LocalDateTime.now();
        JudgeReviewEntity saved = new JudgeReviewEntity();
        saved.setWorkspaceId(workspaceId);
        saved.setRunId(runId);
        saved.setItemId(itemId);
        saved.setEvaluatorKey(key);
        saved.setEvaluatorVersion(version);
        saved.setJudgeLabel(asString(judge.get("label")));
        saved.setHumanLabel(humanLabel);
        saved.setReviewerSlot(SLOT_ADJUDICATOR);
        saved.setProtocolVersion(PROTOCOL_VERSION);
        saved.setNote(note);
        saved.setReviewedBy(reviewer);
        saved.setCreatedAt(now);
        saved.setUpdatedAt(now);
        try {
            judgeReviewMapper.insert(saved);
        } catch (DuplicateKeyException e) {
            throw new EmonLensException(ErrorCode.CONFLICT, "该样本已仲裁，不可覆盖");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", itemId);
        result.put("evaluatorKey", key);
        result.put("evaluatorVersion", version);
        result.put("humanLabel", humanLabel);
        result.put("reviewerSlot", SLOT_ADJUDICATOR);
        result.put("note", note);
        result.put("reviewedBy", reviewer);
        result.put("reviewedAt", now);
        return result;
    }

    private List<JudgeReviewEntity> reviewsOf(String runId, String itemId, String key, Integer version) {
        if (key == null || version == null) {
            return List.of();
        }
        return judgeReviewMapper.selectList(Wrappers.<JudgeReviewEntity>lambdaQuery()
                .eq(JudgeReviewEntity::getRunId, runId)
                .eq(JudgeReviewEntity::getItemId, itemId)
                .eq(JudgeReviewEntity::getEvaluatorKey, key)
                .eq(JudgeReviewEntity::getEvaluatorVersion, version)
                .orderByAsc(JudgeReviewEntity::getCreatedAt));
    }

    private JudgeReviewEntity slot(List<JudgeReviewEntity> reviews, String slot) {
        return reviews.stream().filter(r -> slot.equals(r.getReviewerSlot())).findFirst().orElse(null);
    }

    private String finalLabel(JudgeReviewEntity primary, JudgeReviewEntity second,
                              JudgeReviewEntity adjudicator) {
        if (adjudicator != null) {
            return adjudicator.getHumanLabel();
        }
        if (primary != null && second != null
                && Objects.equals(primary.getHumanLabel(), second.getHumanLabel())) {
            return primary.getHumanLabel();
        }
        return labelOf(primary);
    }

    private String finalNote(JudgeReviewEntity primary, JudgeReviewEntity second,
                             JudgeReviewEntity adjudicator) {
        if (adjudicator != null) return adjudicator.getNote();
        return primary == null ? null : primary.getNote();
    }

    private String finalReviewer(JudgeReviewEntity primary, JudgeReviewEntity second,
                                 JudgeReviewEntity adjudicator) {
        if (adjudicator != null) return adjudicator.getReviewedBy();
        return primary == null ? null : primary.getReviewedBy();
    }

    private LocalDateTime finalReviewedAt(JudgeReviewEntity primary, JudgeReviewEntity second,
                                          JudgeReviewEntity adjudicator) {
        if (adjudicator != null) return adjudicator.getUpdatedAt();
        return primary == null ? null : primary.getUpdatedAt();
    }

    /** 开放 API/CI 没有登录上下文；此时只做校准汇总，不计算“我的评审”与仲裁资格。 */
    private String currentUserIdOrNull() {
        try {
            return CurrentContext.require().getUserId();
        } catch (EmonLensException e) {
            return null;
        }
    }

    private String labelOf(JudgeReviewEntity review) {
        return review == null ? null : review.getHumanLabel();
    }

    private String reviewMode(DatasetRunEntity run) {
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        Object raw = config == null ? null : config.get("reviewMode");
        return raw == null ? MODE_SINGLE : String.valueOf(raw);
    }

    private Map<String, Object> judgeResult(DatasetRunItemEntity item, String evaluatorKey) {
        List<Map<String, Object>> results = parseList(item.getEvaluatorResultsJson());
        if (results == null) {
            return null;
        }
        for (Map<String, Object> result : results) {
            if (!EvaluatorEntity.TYPE_LLM_JUDGE.equals(asString(result.get("type")))) {
                continue;
            }
            if (evaluatorKey == null || evaluatorKey.equals(asString(result.get("evaluatorKey")))) {
                return result;
            }
        }
        return null;
    }

    private Map<String, Object> confusionMatrix(boolean revealed, int reviewed,
                                                int tp, int fp, int tn, int fn) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("positiveLabel", "PASS");
        matrix.put("tp", revealed ? tp : null);
        matrix.put("fp", revealed ? fp : null);
        matrix.put("tn", revealed ? tn : null);
        matrix.put("fn", revealed ? fn : null);
        if (!revealed || reviewed == 0) {
            matrix.put("accuracy", null);
            matrix.put("precision", null);
            matrix.put("recall", null);
            matrix.put("f1", null);
            return matrix;
        }
        double precision = tp + fp == 0 ? 0 : tp * 100.0 / (tp + fp);
        double recall = tp + fn == 0 ? 0 : tp * 100.0 / (tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        matrix.put("accuracy", round((tp + tn) * 100.0 / reviewed));
        matrix.put("precision", round(precision));
        matrix.put("recall", round(recall));
        matrix.put("f1", round(f1));
        return matrix;
    }

    private Double cohenKappa(int n, int agreements, int judgePass, int judgeFail,
                              int humanPass, int humanFail) {
        if (n == 0) return null;
        double po = agreements * 1.0 / n;
        double pe = (judgePass * 1.0 * humanPass + judgeFail * 1.0 * humanFail) / (n * 1.0 * n);
        if (pe >= 1.0) return null;
        return round((po - pe) / (1.0 - pe));
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
