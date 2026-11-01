package io.emonlens.server.engine.eval;

import java.util.List;

import io.emonlens.server.storage.entity.EvaluatorEntity;

import lombok.Data;

/**
 * Claim 聚合：FAIL 一票否决；UNKNOWN 按策略处理；比例只统计 PASS/FAIL。
 *
 * <p>规则固定、可单测，不允许在 evaluator 里写 if/else 分支：
 * 任何 FAIL → FAIL；存在 UNKNOWN 且 policy=BLOCK → UNKNOWN；
 * 全部 UNKNOWN → UNKNOWN；否则 passRatio = PASS/(PASS+FAIL)，
 * 达到 minPassRatio → PASS，不够 → FAIL。</p>
 */
public final class ClaimAggregator {

    public static final String POLICY_BLOCK = "BLOCK";
    public static final String POLICY_FAIL = "FAIL";
    public static final String POLICY_PASS = "PASS";

    private ClaimAggregator() {
    }

    public static Result aggregate(List<Claim> claims, double minPassRatio, String unknownPolicy) {
        Result result = new Result();
        if (claims == null || claims.isEmpty()) {
            result.label = EvaluatorEntity.LABEL_UNKNOWN;
            result.reasonCode = "INVALID_JUDGE_OUTPUT";
            result.reason = "Judge 没有返回任何 claim";
            return result;
        }
        int pass = 0;
        int fail = 0;
        int unknown = 0;
        for (Claim claim : claims) {
            String verdict = claim == null || claim.getVerdict() == null
                    ? "" : claim.getVerdict().trim().toUpperCase();
            if ("PASS".equals(verdict)) {
                pass++;
            } else if ("FAIL".equals(verdict)) {
                fail++;
            } else {
                unknown++;
            }
        }
        result.passCount = pass;
        result.failCount = fail;
        result.unknownCount = unknown;
        int judged = pass + fail;
        result.passRatio = judged == 0 ? 0.0 : (double) pass / judged;

        String policy = unknownPolicy == null || unknownPolicy.isBlank()
                ? POLICY_BLOCK : unknownPolicy.trim().toUpperCase();
        if (fail > 0) {
            result.label = EvaluatorEntity.LABEL_FAIL;
            result.reasonCode = "CLAIM_FAIL";
            result.reason = fail + " 条 claim 未通过（共 " + claims.size() + " 条）";
            return result;
        }
        if (unknown > 0 && POLICY_BLOCK.equals(policy)) {
            result.label = EvaluatorEntity.LABEL_UNKNOWN;
            result.reasonCode = "CLAIM_UNKNOWN";
            result.reason = unknown + " 条 claim 无法确认（共 " + claims.size() + " 条）";
            return result;
        }
        if (unknown > 0 && POLICY_FAIL.equals(policy)) {
            result.label = EvaluatorEntity.LABEL_FAIL;
            result.reasonCode = "CLAIM_UNKNOWN";
            result.reason = unknown + " 条 claim 无法确认，按 FAIL 处理（共 " + claims.size() + " 条）";
            return result;
        }
        if (judged == 0) {
            result.label = EvaluatorEntity.LABEL_UNKNOWN;
            result.reasonCode = "ALL_CLAIMS_UNKNOWN";
            result.reason = "全部 " + claims.size() + " 条 claim 都无法确认";
            return result;
        }
        if (result.passRatio >= minPassRatio) {
            result.label = EvaluatorEntity.LABEL_PASS;
            result.reasonCode = "CLAIM_PASS";
            result.reason = pass + "/" + judged + " 条 claim 通过（通过率 "
                    + Math.round(result.passRatio * 1000.0) / 10.0 + "%）";
            return result;
        }
        result.label = EvaluatorEntity.LABEL_FAIL;
        result.reasonCode = "CLAIM_RATIO_BELOW_THRESHOLD";
        result.reason = pass + "/" + judged + " 条 claim 通过，低于阈值 "
                + Math.round(minPassRatio * 1000.0) / 10.0 + "%";
        return result;
    }

    @Data
    public static class Result {
        private int passCount;
        private int failCount;
        private int unknownCount;
        private double passRatio;
        private String label;
        private String reasonCode;
        private String reason;
    }
}
