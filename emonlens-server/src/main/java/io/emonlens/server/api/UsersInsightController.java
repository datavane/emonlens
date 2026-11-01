package io.emonlens.server.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.time.LocalDateTime;

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
import lombok.RequiredArgsConstructor;

/**
 * Users 用户洞察（对标 Langfuse Users 页）：
 * 按终端用户（user_id）聚合调用量、会话数、错误、成本与最近活动，点击可下钻 trace 列表。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UsersInsightController {

    private final TraceSummaryMapper traceSummaryMapper;

    @GetMapping
    @RequiresPermission(Permissions.TRUST_OVERVIEW_QUERY)
    public ApiResult<PageVO<Map<String, Object>>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String timeFrom,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        // KPI 语义：只统计筛选条件命中的 trace（按 trace 过滤后再按 user 聚合）
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .isNotNull(TraceSummaryEntity::getUserId)
                .eq(hasText(agentKey), TraceSummaryEntity::getAgentKey, agentKey)
                .eq(hasText(environment), TraceSummaryEntity::getEnvironment, environment)
                .ge(timeFrom != null && !timeFrom.isEmpty(), TraceSummaryEntity::getStartTime,
                        parseTime(timeFrom)));

        Map<String, List<TraceSummaryEntity>> byUser = traces.stream()
                .filter(t -> t.getUserId() != null && !t.getUserId().isEmpty())
                .collect(Collectors.groupingBy(TraceSummaryEntity::getUserId));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TraceSummaryEntity>> e : byUser.entrySet()) {
            List<TraceSummaryEntity> ts = e.getValue();
            Map<String, Object> m = new HashMap<>();
            m.put("userId", e.getKey());
            m.put("traceCount", ts.size());
            m.put("sessionCount", ts.stream().map(t -> t.getSessionId()).filter(s -> s != null && !s.isEmpty()).distinct().count());
            m.put("errorCount", ts.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count());
            m.put("totalCostUsd", ts.stream().filter(t -> t.getTotalCostUsd() != null)
                    .map(t -> t.getTotalCostUsd().doubleValue()).mapToDouble(Double::doubleValue).sum());
            m.put("totalDurationMs", ts.stream().filter(t -> t.getDurationMs() != null).mapToLong(TraceSummaryEntity::getDurationMs).sum());
            m.put("firstSeen", ts.stream().map(t -> t.getStartTime()).filter(s -> s != null)
                    .min(Comparator.naturalOrder()).map(Object::toString).orElse(""));
            m.put("lastActivity", ts.stream().map(t -> t.getStartTime()).filter(s -> s != null)
                    .max(Comparator.naturalOrder()).map(Object::toString).orElse(""));
            rows.add(m);
        }
        String kw = query == null ? "" : query.trim().toLowerCase();
        if (!kw.isEmpty()) {
            rows = rows.stream().filter(r -> String.valueOf(r.get("userId")).toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("lastActivity"))).reversed());

        long total = rows.size();
        int page = Math.max(1, pageNumber);
        int size = Math.max(1, Math.min(pageSize, 1000));
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        return ApiResult.ok(PageVO.of(rows.subList(from, to), total, page, size));
    }
    /**
     * 单用户页内详情（Langfuse Users 详情面板对标）：
     * 聚合画像 + 该用户的会话列表 + 最近调用，全部在洞察页内消费，不跳页。
     */
    @GetMapping("/{userId}/detail")
    @RequiresPermission(Permissions.TRUST_OVERVIEW_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String userId) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getUserId, userId));

        Map<String, Object> out = new HashMap<>();
        out.put("userId", userId);
        out.put("traceCount", traces.size());
        out.put("errorCount", traces.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count());
        out.put("totalCostUsd", traces.stream().filter(t -> t.getTotalCostUsd() != null)
                .mapToDouble(t -> t.getTotalCostUsd().doubleValue()).sum());
        out.put("firstSeen", traces.stream().map(TraceSummaryEntity::getStartTime).filter(s -> s != null)
                .min(Comparator.naturalOrder()).map(Object::toString).orElse(""));
        out.put("lastSeen", traces.stream().map(TraceSummaryEntity::getStartTime).filter(s -> s != null)
                .max(Comparator.naturalOrder()).map(Object::toString).orElse(""));


        // 最近调用（最新 20）
        List<Map<String, Object>> recent = traces.stream()
                .sorted((a, b) -> java.util.Optional.ofNullable(b.getStartTime())
                        .orElse(LocalDateTime.MIN).compareTo(java.util.Optional.ofNullable(a.getStartTime()).orElse(LocalDateTime.MIN)))
                .limit(20)
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("traceId", t.getTraceId());
                    m.put("agentKey", t.getAgentKey());
                    m.put("status", t.getStatus());
                    m.put("errorCount", t.getErrorCount());
                    m.put("durationMs", t.getDurationMs());
                    m.put("totalCostUsd", t.getTotalCostUsd() == null ? null : t.getTotalCostUsd().doubleValue());
                    m.put("sessionId", t.getSessionId());
                    m.put("startTime", t.getStartTime() == null ? "" : t.getStartTime().toString());
                    m.put("inputPreview", t.getInputPreview());
                    return m;
                })
                .collect(Collectors.toList());
        out.put("recentTraces", recent);
        return ApiResult.ok(out);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static java.time.LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value);
        } catch (Exception e) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST,
                    "timeFrom must be ISO-8601 local datetime: " + value);
        }
    }
}
