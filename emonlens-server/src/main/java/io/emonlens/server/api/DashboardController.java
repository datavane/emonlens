package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.span.SpanEventStoreRegistry;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiresPermission(Permissions.TRUST_OVERVIEW_QUERY)
@RequiredArgsConstructor
public class DashboardController {

    /**
     * 单窗口内存聚合的 trace 行数上限：仪表盘/用量是应用层聚合（含百分位），
     * 高流量工作空间 30 天可达百万行，全量载入会打满堆。按 startTime 倒序取最近 N 行，
     * 超出时响应带 truncated=true，前端提示“数据已截断”。
     */
    private static final int MAX_WINDOW_TRACES = 50_000;

    private final TraceSummaryMapper traceSummaryMapper;
    private final SpanEventStoreRegistry spanEventStoreRegistry;

    /** selectTraces 结果 + 是否因上限截断 */
    private static final class WindowTraces {
        final List<TraceSummaryEntity> rows;
        final boolean truncated;

        WindowTraces(List<TraceSummaryEntity> rows, boolean truncated) {
            this.rows = rows;
            this.truncated = truncated;
        }
    }

    @GetMapping("/summary")
    public ApiResult<Map<String, Object>> summary(
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String environment) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        LocalDateTime now = LocalDateTime.now();
        java.time.Duration span;
        switch (window) {
            case "1h": span = java.time.Duration.ofHours(1); break;
            case "7d": span = java.time.Duration.ofDays(7); break;
            case "30d": span = java.time.Duration.ofDays(30); break;
            default: span = java.time.Duration.ofHours(24); break;
        }
        LocalDateTime since = now.minus(span);
        LocalDateTime prevSince = since.minus(span);

        WindowTraces current = selectTraces(workspaceId, agentKey, environment, since, null);
        List<TraceSummaryEntity> traces = current.rows;
        // 上一窗口（仅用于环比 delta）
        List<TraceSummaryEntity> prevTraces = selectTraces(workspaceId, agentKey, environment, prevSince, since).rows;

        long traceCount = traces.size();
        long errorCount = traces.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();
        List<Long> latencies = traces.stream()
                .filter(t -> t.getDurationMs() != null)
                .map(TraceSummaryEntity::getDurationMs).sorted().collect(Collectors.toList());
        long p50 = percentile(latencies, 0.5);
        long p95 = percentile(latencies, 0.95);
        double totalCost = traces.stream()
                .filter(t -> t.getTotalCostUsd() != null)
                .mapToDouble(t -> t.getTotalCostUsd().doubleValue()).sum();

        // 上一窗口同指标
        long prevCount = prevTraces.size();
        long prevErrorCount = prevTraces.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();
        List<Long> prevLatencies = prevTraces.stream()
                .filter(t -> t.getDurationMs() != null)
                .map(TraceSummaryEntity::getDurationMs).sorted().collect(Collectors.toList());
        double prevCost = prevTraces.stream()
                .filter(t -> t.getTotalCostUsd() != null)
                .mapToDouble(t -> t.getTotalCostUsd().doubleValue()).sum();

        // 模型聚合（调用量 + 成本）
        Map<String, long[]> modelCount = new java.util.TreeMap<>();
        Map<String, Double> modelCost = new java.util.TreeMap<>();
        for (TraceSummaryEntity t : traces) {
            String model = t.getModelName() == null ? "unknown" : t.getModelName();
            modelCount.computeIfAbsent(model, k -> new long[2]);
            modelCount.get(model)[0]++;
            if (t.getErrorCount() != null && t.getErrorCount() > 0) {
                modelCount.get(model)[1]++;
            }
            if (t.getTotalCostUsd() != null) {
                modelCost.merge(model, t.getTotalCostUsd().doubleValue(), Double::sum);
            }
        }
        List<Map<String, Object>> modelBreakdown = new ArrayList<>();
        modelCount.forEach((model, c) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("model", model);
            m.put("traces", c[0]);
            m.put("errors", c[1]);
            m.put("costUsd", Math.round(modelCost.getOrDefault(model, 0.0) * 10000) / 10000.0);
            modelBreakdown.add(m);
        });
        modelBreakdown.sort((a, b) -> Long.compare((long) b.get("traces"), (long) a.get("traces")));

        Map<String, Long> dailyCounts = new java.util.LinkedHashMap<>();
        traces.forEach(t -> {
            String day = t.getStartTime() != null
                    ? t.getStartTime().toLocalDate().toString() : "unknown";
            dailyCounts.merge(day, 1L, Long::sum);
        });

        Map<String, Long> modelDist = new java.util.LinkedHashMap<>();
        traces.forEach(t -> {
            String model = t.getModelName() == null ? "unknown" : t.getModelName();
            modelDist.merge(model, 1L, Long::sum);
        });

        // 趋势桶：按窗口粒度分桶（1h→5分钟，24h→小时，7d/30d→天），返回有序数组供前端画趋势图
        java.time.format.DateTimeFormatter fmt = bucketFormatter(window);
        Map<String, long[]> bucketMap = new java.util.TreeMap<>();
        for (TraceSummaryEntity t : traces) {
            if (t.getStartTime() == null) {
                continue;
            }
            String key = fmt.format(t.getStartTime());
            long[] counts = bucketMap.computeIfAbsent(key, k -> new long[2]);
            counts[0]++;
            if (t.getErrorCount() != null && t.getErrorCount() > 0) {
                counts[1]++;
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        bucketMap.forEach((k, v) -> {
            Map<String, Object> point = new HashMap<>();
            point.put("time", k);
            point.put("traces", v[0]);
            point.put("errors", v[1]);
            trend.add(point);
        });

        // 延迟分布（对标 Langfuse Latency Histogram）
        String[] labels = {"0-1s", "1-3s", "3-10s", "10-30s", ">30s"};
        long[][] ranges = {{0, 1000}, {1000, 3000}, {3000, 10000}, {10000, 30000}, {30000, Long.MAX_VALUE}};
        List<Map<String, Object>> latencyHistogram = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int idx = i;
            long count = latencies.stream().filter(ms -> ms >= ranges[idx][0] && ms < ranges[idx][1]).count();
            Map<String, Object> b = new HashMap<>();
            b.put("label", labels[i]);
            b.put("count", count);
            latencyHistogram.add(b);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("traceCount", traceCount);
        result.put("truncated", current.truncated);
        result.put("latencyHistogram", latencyHistogram);
        result.put("errorCount", errorCount);
        result.put("errorRate", traceCount > 0
                ? String.format("%.1f%%", errorCount * 100.0 / traceCount) : "0%");
        result.put("p50LatencyMs", p50);
        result.put("p95LatencyMs", p95);
        result.put("totalCostUsd", String.format("%.4f", totalCost));
        // 环比（上一窗口为 0 时不给 delta）
        result.put("delta", new HashMap<String, Object>() {{
            if (prevCount > 0) {
                put("traceCount", pctDelta(traceCount, prevCount));
                put("errorCount", pctDelta(errorCount, prevErrorCount));
                put("p50LatencyMs", percentile(prevLatencies, 0.5) > 0 ? pctDelta(p50, percentile(prevLatencies, 0.5)) : null);
                put("p95LatencyMs", percentile(prevLatencies, 0.95) > 0 ? pctDelta(p95, percentile(prevLatencies, 0.95)) : null);
                put("totalCostUsd", prevCost > 0 ? pctDelta(totalCost, prevCost) : null);
            }
        }});
        result.put("modelBreakdown", modelBreakdown);
        result.put("dailyTrend", dailyCounts);
        result.put("modelDistribution", modelDist);
        result.put("trend", trend);
        return ApiResult.ok(result);
    }

    /** 用量与成本（对标 Langfuse Usage & Cost）：按天聚合调用量/错误/成本；支持维度聚合下钻（model/agent/user） */
    @GetMapping("/usage")
    public ApiResult<Map<String, Object>> usage(
            @RequestParam(defaultValue = "30d") String window,
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String environment,
            @RequestParam(defaultValue = "model") String dimension) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        java.time.Duration span;
        switch (window) {
            case "1h": span = java.time.Duration.ofHours(1); break;
            case "24h": span = java.time.Duration.ofHours(24); break;
            case "7d": span = java.time.Duration.ofDays(7); break;
            default: span = java.time.Duration.ofDays(30); break;
        }
        WindowTraces current = selectTraces(workspaceId, agentKey, environment, LocalDateTime.now().minus(span), null);
        List<TraceSummaryEntity> traces = current.rows;

        Map<String, Object[]> byDay = new java.util.TreeMap<>();
        for (TraceSummaryEntity t : traces) {
            if (t.getStartTime() == null) {
                continue;
            }
            String day = t.getStartTime().toLocalDate().toString();
            Object[] row = byDay.computeIfAbsent(day, k -> new Object[]{0L, 0L, 0.0});
            row[0] = (Long) row[0] + 1;
            if (t.getErrorCount() != null && t.getErrorCount() > 0) {
                row[1] = (Long) row[1] + 1;
            }
            if (t.getTotalCostUsd() != null) {
                row[2] = (Double) row[2] + t.getTotalCostUsd().doubleValue();
            }
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        byDay.forEach((day, row) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("day", day);
            m.put("traces", row[0]);
            m.put("errors", row[1]);
            m.put("costUsd", Math.round((Double) row[2] * 1000000) / 1000000.0);
            daily.add(m);
        });

        double totalCost = traces.stream()
                .filter(t -> t.getTotalCostUsd() != null)
                .mapToDouble(t -> t.getTotalCostUsd().doubleValue()).sum();
        long totalErrors = traces.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();

        // tokens 汇总（按 span_event 聚合，含输入/输出/缓存命中/总量），按天分布供前端画叠加柱状图
        Map<String, long[]> tokenAgg = tokensByDay(workspaceId, traces);
        long inputTokens = 0, outputTokens = 0, cachedInputTokens = 0;
        for (long[] v : tokenAgg.values()) {
            inputTokens += v[0];
            outputTokens += v[1];
            cachedInputTokens += v[2];
        }
        List<Map<String, Object>> tokenDaily = new ArrayList<>();
        tokenAgg.forEach((day, v) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("day", day);
            m.put("inputTokens", v[0]);
            m.put("outputTokens", v[1]);
            m.put("cachedInputTokens", v[2]);
            tokenDaily.add(m);
        });

        Map<String, Object> result = new HashMap<>();
        result.put("traceCount", traces.size());
        result.put("truncated", current.truncated);
        result.put("errorCount", totalErrors);
        result.put("errorRate", traces.isEmpty() ? 0.0
                : Math.round(totalErrors * 10000.0 / traces.size()) / 100.0);
        result.put("totalCostUsd", Math.round(totalCost * 1000000) / 1000000.0);
        result.put("avgCostUsd", traces.isEmpty() ? 0.0
                : Math.round(totalCost * 1000000.0 / traces.size()) / 1000000.0);
        result.put("inputTokens", inputTokens);
        result.put("outputTokens", outputTokens);
        result.put("cachedInputTokens", cachedInputTokens);
        result.put("totalTokens", inputTokens + outputTokens);
        result.put("daily", daily);
        result.put("tokenDaily", tokenDaily);
        result.put("models", modelBreakdownOf(traces));
        // 维度下钻（Langfuse Usage 多维聚合对标）：model | agent | user
        String dim = "agent".equals(dimension) || "user".equals(dimension) ? dimension : "model";
        result.put("dimension", dim);
        result.put("rows", breakdownOf(traces, dim));
        return ApiResult.ok(result);
    }

    /** 窗口内 span 级 tokens 按天聚合：day -> {inputTokens, outputTokens, cachedInputTokens}（一次 SQL，内存按 traceId 关联日期） */
    private Map<String, long[]> tokensByDay(String workspaceId, List<TraceSummaryEntity> traces) {
        Map<String, java.time.LocalDate> traceDay = new HashMap<>();
        for (TraceSummaryEntity t : traces) {
            if (t.getTraceId() != null && t.getStartTime() != null) {
                traceDay.put(t.getTraceId(), t.getStartTime().toLocalDate());
            }
        }
        if (traceDay.isEmpty()) {
            return new java.util.TreeMap<>();
        }
        Map<String, long[]> byTrace = spanEventStoreRegistry.active()
                .sumTokensByTraceIds(workspaceId, new ArrayList<>(traceDay.keySet()));
        Map<String, long[]> agg = new java.util.TreeMap<>();
        for (Map.Entry<String, long[]> e : byTrace.entrySet()) {
            java.time.LocalDate day = traceDay.get(e.getKey());
            if (day == null) {
                continue;
            }
            long[] v = agg.computeIfAbsent(day.toString(), k -> new long[3]);
            long[] src = e.getValue();
            v[0] += src[0];
            v[1] += src[1];
            v[2] += src[2];
        }
        return agg;
    }

    /** 按维度聚合：traces / errors / cost / 最近活动，供用量页维度切换下钻 */
    private List<Map<String, Object>> breakdownOf(List<TraceSummaryEntity> traces, String dimension) {
        java.util.function.Function<TraceSummaryEntity, String> keyFn =
                "agent".equals(dimension) ? TraceSummaryEntity::getAgentKey
                        : "user".equals(dimension) ? t -> t.getUserId() == null || t.getUserId().isEmpty() ? "（未标注）" : t.getUserId()
                        : t -> t.getModelName() == null ? "unknown" : t.getModelName();
        Map<String, List<TraceSummaryEntity>> grouped = new java.util.TreeMap<>();
        for (TraceSummaryEntity t : traces) {
            grouped.computeIfAbsent(keyFn.apply(t), k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TraceSummaryEntity>> e : grouped.entrySet()) {
            List<TraceSummaryEntity> ts = e.getValue();
            long errors = ts.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();
            double cost = ts.stream().filter(t -> t.getTotalCostUsd() != null)
                    .mapToDouble(t -> t.getTotalCostUsd().doubleValue()).sum();
            String last = ts.stream().map(TraceSummaryEntity::getStartTime).filter(s -> s != null)
                    .max(Comparator.naturalOrder()).map(Object::toString).orElse("");
            Map<String, Object> m = new HashMap<>();
            m.put("name", e.getKey());
            m.put("traces", ts.size());
            m.put("errors", errors);
            m.put("costUsd", Math.round(cost * 10000) / 10000.0);
            m.put("lastActivity", last);
            rows.add(m);
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("traces")).longValue(),
                ((Number) a.get("traces")).longValue()));
        return rows;
    }

    private List<Map<String, Object>> modelBreakdownOf(List<TraceSummaryEntity> traces) {
        Map<String, long[]> modelCount = new java.util.TreeMap<>();
        Map<String, Double> modelCost = new java.util.TreeMap<>();
        for (TraceSummaryEntity t : traces) {
            String model = t.getModelName() == null ? "unknown" : t.getModelName();
            modelCount.computeIfAbsent(model, k -> new long[2]);
            modelCount.get(model)[0]++;
            if (t.getErrorCount() != null && t.getErrorCount() > 0) {
                modelCount.get(model)[1]++;
            }
            if (t.getTotalCostUsd() != null) {
                modelCost.merge(model, t.getTotalCostUsd().doubleValue(), Double::sum);
            }
        }
        List<Map<String, Object>> breakdown = new ArrayList<>();
        modelCount.forEach((model, c) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("model", model);
            m.put("traces", c[0]);
            m.put("errors", c[1]);
            m.put("costUsd", Math.round(modelCost.getOrDefault(model, 0.0) * 10000) / 10000.0);
            breakdown.add(m);
        });
        breakdown.sort((a, b) -> Long.compare((long) b.get("traces"), (long) a.get("traces")));
        return breakdown;
    }

    private WindowTraces selectTraces(String workspaceId, String agentKey,
                                      String environment,
                                      LocalDateTime from, LocalDateTime to) {
        var wrapper = Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .ge(TraceSummaryEntity::getStartTime, from);
        if (to != null) {
            wrapper.lt(TraceSummaryEntity::getStartTime, to);
        }
        if (agentKey != null && !agentKey.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getAgentKey, agentKey);
        }
        if (environment != null && !environment.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getEnvironment, environment);
        }
        // 多取 1 行用于精确判断是否截断；倒序保证保留的是最近数据
        List<TraceSummaryEntity> rows = traceSummaryMapper.selectList(wrapper
                .orderByDesc(TraceSummaryEntity::getStartTime)
                .last("LIMIT " + (MAX_WINDOW_TRACES + 1)));
        boolean truncated = rows.size() > MAX_WINDOW_TRACES;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, MAX_WINDOW_TRACES));
        }
        return new WindowTraces(rows, truncated);
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.min(sorted.size() - 1, Math.round(p * sorted.size()));
        return sorted.get(Math.max(idx, 0));
    }

    private Double pctDelta(double cur, double prev) {
        if (prev <= 0) {
            return null;
        }
        return Math.round((cur - prev) * 1000.0 / prev) / 10.0;
    }

    private java.time.format.DateTimeFormatter bucketFormatter(String window) {
        switch (window) {
            case "1h": return java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            case "7d":
            case "30d": return java.time.format.DateTimeFormatter.ofPattern("MM-dd");
            default: return java.time.format.DateTimeFormatter.ofPattern("dd日HH:00");
        }
    }
}
