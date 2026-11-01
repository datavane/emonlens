package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.api.dto.PageVO;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 人工评分：直接对 Trace 打分（写入 et_eval_result，label=PASS/FAIL）。 */
@RestController
@RequestMapping("/api/v1/traces/{traceId}/scores")
@RequiredArgsConstructor
public class HumanScoreController {

    private final TraceSummaryMapper traceSummaryMapper;
    private final EvalResultMapper evalResultMapper;

    @Data
    public static class ScoreRequest {
        private Double score;      // 0-10
        private String comment;
    }

    @PostMapping
    @RequiresPermission("workspace:trust:evaluation:run")
    @Audit(action = "human.score", resourceType = "trace")
    public ApiResult<EvalResultEntity> score(@PathVariable String traceId,
                                             @RequestBody ScoreRequest request) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .eq(TraceSummaryEntity::getTraceId, traceId));
        if (summary == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        if (!workspaceId.equals(summary.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        double score = request.getScore() == null ? 5.0 : Math.max(0, Math.min(10, request.getScore()));
        String label = score >= 6.0 ? "PASS" : "FAIL";

        EvalResultEntity result = new EvalResultEntity();
        result.setEvaluatorId("human-score");
        result.setEvaluatorKey("human-score");
        result.setEvaluatorVersion(1);
        result.setWorkspaceId(workspaceId);
        result.setAgentKey(summary.getAgentKey());
        result.setAgentVersion(summary.getAgentVersion());
        result.setTraceId(traceId);
        result.setAttempt(1);
        result.setScore(java.math.BigDecimal.valueOf(score).setScale(2, java.math.RoundingMode.HALF_UP));
        result.setLabel(label);
        result.setConfidence(java.math.BigDecimal.ONE);
        result.setReason(request.getComment() == null ? "human score" : request.getComment());
        result.setJudgeModel("human");
        result.setCreatedAt(LocalDateTime.now());
        evalResultMapper.insert(result);
        return ApiResult.ok(result);
    }

    @GetMapping
    @RequiresPermission("workspace:trust:evaluation:query")
    public ApiResult<PageVO<EvalResultEntity>> list(
            @PathVariable String traceId,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        // 评分行含人工评论（reason），必须与写侧一致做工作空间过滤，防跨租户按 traceId 探测
        Page<EvalResultEntity> page = evalResultMapper.selectPage(PageVO.safePage(pageNumber, pageSize),
                Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                        .eq(EvalResultEntity::getTraceId, traceId));
        return ApiResult.ok(PageVO.from(page, page.getRecords()));
    }
}
