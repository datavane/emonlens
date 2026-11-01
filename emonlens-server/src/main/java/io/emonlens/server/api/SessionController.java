package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.query.SessionAggregate;
import io.emonlens.server.storage.span.SpanEventStoreRegistry;

import lombok.RequiredArgsConstructor;

/**
 * 会话（Sessions，Langfuse 一等导航项对标）：把同一 sessionId 的多次调用聚合成对话级视图。
 * 列表 = 按 sessionId 聚合（调用数/错误/成本/用户/起止）；详情 = 会话内全部 trace 时间线 + 汇总。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final TraceSummaryMapper traceSummaryMapper;
    private final io.emonlens.server.storage.mapper.EvalResultMapper evalResultMapper;
    private final io.emonlens.server.storage.mapper.FindingMapper findingMapper;
    private final SpanEventStoreRegistry spanEventStoreRegistry;

    @GetMapping
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<PageVO<Map<String, Object>>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String timeFrom,
            @RequestParam(required = false) Boolean hasError,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        String kw = query == null ? "" : query.trim();
        String kwLike = kw.isEmpty() ? null
                : "%" + io.emonlens.server.storage.span.SpanEventStore.escapeLike(kw) + "%";
        boolean errorOnly = Boolean.TRUE.equals(hasError);
        LocalDateTime from = parseTime(timeFrom);
        int size = Math.max(1, Math.min(pageSize, 200));
        int page = Math.max(1, pageNumber);

        long total = traceSummaryMapper.sessionCount(workspaceId, agentKey, userId, environment, from, kwLike, errorOnly);
        if (total == 0) {
            return ApiResult.ok(PageVO.of(new ArrayList<>(), 0, page, size));
        }
        List<SessionAggregate> aggregates = traceSummaryMapper.sessionPage(
                workspaceId, agentKey, userId, environment, from, kwLike, errorOnly, size, (page - 1) * size);
        List<String> sessionIds = aggregates.stream()
                .map(SessionAggregate::getSessionId).collect(Collectors.toList());

        // userIds / agentKeys 只查当前页会话，避免全量 trace 回表
        Map<String, List<String>> usersBySession = new HashMap<>();
        for (SessionAggregate link : traceSummaryMapper.sessionUserLinks(workspaceId, sessionIds)) {
            usersBySession.computeIfAbsent(link.getSessionId(), k -> new ArrayList<>()).add(link.getUserId());
        }
        Map<String, List<String>> agentsBySession = new HashMap<>();
        for (SessionAggregate link : traceSummaryMapper.sessionAgentLinks(workspaceId, sessionIds)) {
            agentsBySession.computeIfAbsent(link.getSessionId(), k -> new ArrayList<>()).add(link.getAgentKey());
        }

        List<Map<String, Object>> rows = new ArrayList<>(aggregates.size());
        for (SessionAggregate a : aggregates) {
            Map<String, Object> m = new HashMap<>();
            m.put("sessionId", a.getSessionId());
            String name = a.getName() == null ? "" : a.getName();
            m.put("name", name.length() > 60 ? name.substring(0, 60) + "…" : name);
            m.put("traceCount", a.getTraceCount() == null ? 0L : a.getTraceCount());
            m.put("errorCount", a.getErrorCount() == null ? 0L : a.getErrorCount());
            m.put("totalCostUsd", a.getTotalCostUsd() == null ? java.math.BigDecimal.ZERO : a.getTotalCostUsd());
            m.put("totalDurationMs", a.getTotalDurationMs() == null ? 0L : a.getTotalDurationMs());
            m.put("userIds", sortedDistinct(usersBySession.get(a.getSessionId())));
            m.put("agentKeys", sortedDistinct(agentsBySession.get(a.getSessionId())));
            m.put("firstSeen", a.getFirstSeen() == null ? "" : a.getFirstSeen().toString());
            m.put("lastActivity", a.getLastActivity() == null ? "" : a.getLastActivity().toString());
            rows.add(m);
        }
        return ApiResult.ok(PageVO.of(rows, total, page, size));
    }

    private static List<String> sortedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(values);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST,
                    "timeFrom must be ISO-8601 local datetime: " + value);
        }
    }

    @GetMapping("/{sessionId}")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String sessionId) {
        List<TraceSummaryEntity> sessionTraces = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                        .eq(TraceSummaryEntity::getSessionId, sessionId)
                        .orderByAsc(TraceSummaryEntity::getStartTime));
        if (sessionTraces.isEmpty()) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.NOT_FOUND,
                    "session not found: " + sessionId);
        }
        // 人评聚合（Langfuse user/session score 联动对标）：judgeModel='human' 的评分按 trace 平均
        List<String> traceIds = sessionTraces.stream().map(TraceSummaryEntity::getTraceId).collect(Collectors.toList());
        List<io.emonlens.server.storage.entity.EvalResultEntity> allResults =
                evalResultMapper.selectList(Wrappers.<io.emonlens.server.storage.entity.EvalResultEntity>lambdaQuery()
                        .in(io.emonlens.server.storage.entity.EvalResultEntity::getTraceId, traceIds));
        Map<String, List<io.emonlens.server.storage.entity.EvalResultEntity>> humanByTrace = allResults.stream()
                .filter(r -> "human".equals(r.getJudgeModel()))
                .collect(Collectors.groupingBy(io.emonlens.server.storage.entity.EvalResultEntity::getTraceId));
        Map<String, Long> autoByTrace = allResults.stream()
                .filter(r -> !"human".equals(r.getJudgeModel()))
                .collect(Collectors.groupingBy(io.emonlens.server.storage.entity.EvalResultEntity::getTraceId, Collectors.counting()));
        // 异常发现聚合（会话 ↔ 告警联动）：每 trace 的 finding 数与最高严重级
        List<io.emonlens.server.storage.entity.FindingEntity> sessionFindings =
                findingMapper.selectList(Wrappers.<io.emonlens.server.storage.entity.FindingEntity>lambdaQuery()
                        .in(io.emonlens.server.storage.entity.FindingEntity::getTraceId, traceIds));
        Map<String, List<io.emonlens.server.storage.entity.FindingEntity>> findingsByTrace = sessionFindings.stream()
                .collect(Collectors.groupingBy(io.emonlens.server.storage.entity.FindingEntity::getTraceId));
        Map<String, Object> out = new HashMap<>();
        out.put("sessionId", sessionId);
        out.putAll(sessionRow(sessionId, sessionTraces));
        // 会话 tokens 汇总（span 级聚合，一次查询；供详情页 KPI）
        long[] tokens = spanTokensOf(traceIds);
        out.put("totalTokens", tokens[0] + tokens[1]);
        out.put("inputTokens", tokens[0]);
        out.put("outputTokens", tokens[1]);
        out.put("cachedInputTokens", tokens[2]);
        out.put("traces", sessionTraces.stream().map(t -> {
            Map<String, Object> vo = toVO(t);
            List<io.emonlens.server.storage.entity.EvalResultEntity> human = humanByTrace.get(t.getTraceId());
            if (human != null && !human.isEmpty()) {
                double avg = human.stream()
                        .filter(r -> r.getScore() != null)
                        .mapToDouble(r -> r.getScore().doubleValue()).average().orElse(0);
                vo.put("humanScore", Math.round(avg * 10) / 10.0);
                vo.put("humanScoreCount", human.size());
            }
            Long autoCount = autoByTrace.get(t.getTraceId());
            if (autoCount != null && autoCount > 0) {
                vo.put("autoEvalCount", autoCount.intValue());
            }
            List<io.emonlens.server.storage.entity.FindingEntity> fs = findingsByTrace.get(t.getTraceId());
            if (fs != null && !fs.isEmpty()) {
                vo.put("findingCount", fs.size());
                vo.put("topSeverity", fs.stream()
                        .map(io.emonlens.server.storage.entity.FindingEntity::getSeverity)
                        .filter(s -> s != null)
                        .min(Comparator.comparingInt(SessionController::severityRank))
                        .orElse(""));
            }
            return vo;
        }).collect(Collectors.toList()));
        return ApiResult.ok(out);
    }

    static int severityRank(String s) {
        switch (s) {
            case "CRITICAL": return 0;
            case "HIGH": return 1;
            case "MEDIUM": return 2;
            default: return 3;
        }
    }

    private Map<String, Object> sessionRow(String sessionId, List<TraceSummaryEntity> ts) {        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", sessionId);
        // 会话自动命名（Langfuse 同语义）：取时间最早且带输入摘要的 trace，截断 60 字
        String name = ts.stream()
                .filter(t -> t.getInputPreview() != null && !t.getInputPreview().isEmpty())
                .min(Comparator.comparing(t -> t.getStartTime() == null ? LocalDateTime.MIN : t.getStartTime()))
                .map(t -> t.getInputPreview())
                .map(s -> s.length() > 60 ? s.substring(0, 60) + "…" : s)
                .orElse("");
        m.put("name", name);
        m.put("traceCount", ts.size());
        m.put("errorCount", ts.stream().mapToInt(t -> t.getErrorCount() == null ? 0 : t.getErrorCount()).sum());
        m.put("totalCostUsd", ts.stream().filter(t -> t.getTotalCostUsd() != null)
                .map(t -> t.getTotalCostUsd()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        m.put("totalDurationMs", ts.stream().filter(t -> t.getDurationMs() != null).mapToLong(TraceSummaryEntity::getDurationMs).sum());
        m.put("userIds", ts.stream().map(TraceSummaryEntity::getUserId)
                .filter(u -> u != null && !u.isEmpty()).distinct().collect(Collectors.toList()));
        m.put("agentKeys", ts.stream().map(TraceSummaryEntity::getAgentKey)
                .filter(a -> a != null && !a.isEmpty()).distinct().collect(Collectors.toList()));
        m.put("firstSeen", ts.stream().map(TraceSummaryEntity::getStartTime).filter(s -> s != null)
                .min(Comparator.naturalOrder()).map(Object::toString).orElse(""));
        m.put("lastActivity", ts.stream().map(TraceSummaryEntity::getStartTime).filter(s -> s != null)
                .max(Comparator.naturalOrder()).map(Object::toString).orElse(""));
        return m;
    }

    private Map<String, Object> toVO(TraceSummaryEntity t) {
        Map<String, Object> m = new HashMap<>();
        m.put("traceId", t.getTraceId());
        m.put("agentKey", t.getAgentKey());
        m.put("status", t.getStatus());
        m.put("errorCount", t.getErrorCount());
        m.put("durationMs", t.getDurationMs());
        m.put("totalCostUsd", t.getTotalCostUsd());
        m.put("userId", t.getUserId());
        m.put("inputPreview", t.getInputPreview());
        m.put("startTime", t.getStartTime());
        return m;
    }

    /** 会话内 span 级 tokens 聚合：{输入, 输出, 缓存命中}（一次查询，仅详情页使用） */
    private long[] spanTokensOf(List<String> traceIds) {
        if (traceIds.isEmpty()) {
            return new long[]{0L, 0L, 0L};
        }
        java.util.Map<String, long[]> byTrace = spanEventStoreRegistry.active()
                .sumTokensByTraceIds(CurrentContext.require().getWorkspaceId(), traceIds);
        long in = 0, out = 0, cached = 0;
        for (long[] v : byTrace.values()) {
            in += v[0];
            out += v[1];
            cached += v[2];
        }
        return new long[]{in, out, cached};
    }
}
