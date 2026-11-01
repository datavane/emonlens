package io.emonlens.server.storage.trace;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.ModelPriceEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.ModelPriceMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.span.SpanEventStore;
import io.emonlens.server.storage.span.SpanEventStoreRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * span 落库 + trace summary 组装（纯持久化，不含事件编排）。
 * span 读写经由 SpanEventStore 插件（emonlens.storage.mode 装配：mysql / starrocks）；
 * trace summary 恒在关系库。
 * 完成判定：根 span（parent_span_id 为空）已有结束时间即 COMPLETED；
 * 根 span 未结束的 RUNNING trace 由调用方通过 sweepIdleTraces 按 idle 超时认领判定。
 * persistBatch / sweepIdleTraces 返回"本轮新完成的 summaries"，由引擎层据此入队 TRACE_COMPLETED 事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceAssemblyService {

    private final SpanEventStoreRegistry spanStoreRegistry;
    private final TraceSummaryMapper traceSummaryMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private SpanEventStore spanStore() {
        return spanStoreRegistry.active();
    }

    @Transactional
    public List<TraceSummaryEntity> persistBatch(List<SpanEventEntity> batch) {
        LocalDateTime now = LocalDateTime.now();
        java.util.Map<String, ModelPriceEntity> priceCache = new java.util.HashMap<>();
        for (SpanEventEntity span : batch) {
            span.setIngestTime(now);
            estimateCost(span, priceCache);
        }
        // 仅新写入行参与 summary 聚合：重复上报（SDK 重试/重放）不虚增 spanCount/errorCount
        SpanEventStore.InsertResult result = spanStore().insertBatch(batch);
        int droppedDuplicates = result.duplicates;
        List<TraceSummaryEntity> completedNow = new ArrayList<>();
        for (Map.Entry<String, List<SpanEventEntity>> entry : groupByTrace(result.written).entrySet()) {
            TraceSummaryEntity completed = upsertSummary(entry.getValue(), now);
            if (completed != null) {
                completedNow.add(completed);
            }
        }
        if (droppedDuplicates > 0) {
            // 跨存储补偿（StarRocks 模式）：上一轮 span 已写入但 summary 更新失败/崩溃时，
            // 重试批次全部命中幂等去重 -> written 为空 -> summary 永远缺失、trace 不可见。
            // 对输入批次里 summary 仍缺失的 trace 做一次全量重建。
            repairMissingSummaries(batch, now, completedNow);
            log.info("span batch persist: {} duplicates skipped (idempotent)", droppedDuplicates);
        }
        if (result.rejected > 0) {
            log.warn("span batch persist: {} invalid spans rejected (field length/constraint)", result.rejected);
        }
        return completedNow;
    }

    private void repairMissingSummaries(List<SpanEventEntity> batch, LocalDateTime now,                                        List<TraceSummaryEntity> completedNow) {
        Map<String, String> workspaceByTrace = new java.util.LinkedHashMap<>();
        for (SpanEventEntity span : batch) {
            if (span.getTraceId() != null) {
                workspaceByTrace.putIfAbsent(span.getTraceId(),
                        span.getWorkspaceId() == null ? "" : span.getWorkspaceId());
            }
        }
        for (Map.Entry<String, String> entry : workspaceByTrace.entrySet()) {
            String traceId = entry.getKey();
            String workspaceId = entry.getValue();
            TraceSummaryEntity existing = traceSummaryMapper.selectOne(
                    Wrappers.<TraceSummaryEntity>lambdaQuery()
                            .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                            .eq(TraceSummaryEntity::getTraceId, traceId));
            if (existing != null) {
                continue;
            }
            List<SpanEventEntity> spans = spanStore().loadByTrace(workspaceId, traceId);
            if (spans.isEmpty()) {
                continue;
            }
            log.warn("repairing missing trace summary, workspace={}, traceId={}", workspaceId, traceId);
            TraceSummaryEntity completed = upsertSummary(spans, now);
            if (completed != null && completedNow.stream()
                    .noneMatch(s -> Objects.equals(s.getId(), completed.getId()))) {
                completedNow.add(completed);
            }
        }
    }

    /**
     * summary 计数对账：对近期活跃且已完成的 trace，用数据面真实 span 数校正
     * spanCount/errorCount（StarRocks 等非事务数据面在跨存储窗口可能丢计数更新）。
     * @return 修复的 trace 数
     */
    public int reconcileCompleted(LocalDateTime activeSince, int limit) {
        List<TraceSummaryEntity> recent = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getStatus, TraceSummaryEntity.STATUS_COMPLETED)
                        .ge(TraceSummaryEntity::getLastSpanAt, activeSince)
                        .orderByDesc(TraceSummaryEntity::getLastSpanAt)
                        .last("LIMIT " + Math.max(1, limit)));
        if (recent.isEmpty()) {
            return 0;
        }
        Map<String, List<TraceSummaryEntity>> byWorkspace = new HashMap<>();
        for (TraceSummaryEntity summary : recent) {
            byWorkspace.computeIfAbsent(summary.getWorkspaceId(), k -> new ArrayList<>()).add(summary);
        }
        int repaired = 0;
        for (Map.Entry<String, List<TraceSummaryEntity>> entry : byWorkspace.entrySet()) {
            List<String> traceIds = entry.getValue().stream()
                    .map(TraceSummaryEntity::getTraceId).collect(java.util.stream.Collectors.toList());
            Map<String, Long> actual = spanStore().countByTraceIds(entry.getKey(), traceIds);
            for (TraceSummaryEntity summary : entry.getValue()) {
                long actualCount = actual.getOrDefault(summary.getTraceId(), 0L);
                int recorded = summary.getSpanCount() == null ? 0 : summary.getSpanCount();
                if (actualCount == recorded) {
                    continue;
                }
                try {
                    List<SpanEventEntity> spans = spanStore().loadByTrace(
                            summary.getWorkspaceId(), summary.getTraceId());
                    if (spans.isEmpty()) {
                        continue;
                    }
                    long errorCount = spans.stream()
                            .filter(s -> SpanEventEntity.STATUS_ERROR.equals(s.getStatus())).count();
                    traceSummaryMapper.update(null, Wrappers.<TraceSummaryEntity>lambdaUpdate()
                            .eq(TraceSummaryEntity::getId, summary.getId())
                            .set(TraceSummaryEntity::getSpanCount, spans.size())
                            .set(TraceSummaryEntity::getErrorCount, (int) errorCount));
                    repaired++;
                    log.warn("trace summary count reconciled, workspace={}, traceId={}, {} -> {}",
                            summary.getWorkspaceId(), summary.getTraceId(), recorded, spans.size());
                } catch (Exception e) {
                    log.error("trace summary reconcile failed, traceId={}", summary.getTraceId(), e);
                }
            }
        }
        return repaired;
    }

    private Map<String, List<SpanEventEntity>> groupByTrace(List<SpanEventEntity> batch) {
        Map<String, List<SpanEventEntity>> byTrace = new HashMap<>();
        for (SpanEventEntity span : batch) {
            byTrace.computeIfAbsent(span.getTraceId(), k -> new ArrayList<>()).add(span);
        }
        return byTrace;
    }

    /** @return 若该 trace 本轮瞬时变为 COMPLETED 返回最新 summary，否则返回 null */
    private TraceSummaryEntity upsertSummary(List<SpanEventEntity> spans, LocalDateTime now) {
        SpanEventEntity first = spans.get(0);
        TraceSummaryEntity existing = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, first.getWorkspaceId())
                .eq(TraceSummaryEntity::getTraceId, first.getTraceId()));

        LocalDateTime minStart = spans.stream().map(SpanEventEntity::getStartTime)
                .min(LocalDateTime::compareTo).orElse(now);
        LocalDateTime maxEnd = spans.stream().map(SpanEventEntity::getEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        long errorCount = spans.stream()
                .filter(s -> SpanEventEntity.STATUS_ERROR.equals(s.getStatus())).count();
        SpanEventEntity rootSpan = spans.stream()
                .filter(s -> s.getParentSpanId() == null || s.getParentSpanId().isEmpty())
                .findFirst().orElse(null);
        boolean rootEnded = rootSpan != null && rootSpan.getEndTime() != null;

        if (existing == null) {
            TraceSummaryEntity summary = new TraceSummaryEntity();
            summary.setWorkspaceId(first.getWorkspaceId());
            summary.setAgentKey(first.getAgentKey());
            summary.setAgentVersion(first.getAgentVersion());
            summary.setTraceId(first.getTraceId());
            summary.setSpanCount(spans.size());
            summary.setErrorCount((int) errorCount);
            summary.setRootSpanId(rootSpan == null ? null : rootSpan.getSpanId());
            summary.setStartTime(minStart);
            summary.setEndTime(maxEnd);
            summary.setDurationMs(maxEnd == null ? null : Duration.between(minStart, maxEnd).toMillis());
            summary.setLastSpanAt(now);
            summary.setStatus(rootEnded ? TraceSummaryEntity.STATUS_COMPLETED : TraceSummaryEntity.STATUS_RUNNING);
            // 会话归属：优先根 span 的 sessionId，其次任意 span 透传；缺失则保持 null
            summary.setSessionId(rootSpan != null && rootSpan.getSessionId() != null && !rootSpan.getSessionId().isEmpty()
                    ? rootSpan.getSessionId()
                    : firstNonNull(spans, SpanEventEntity::getSessionId));
            // 用户归属（用户洞察聚合维度）：同 sessionId 语义，优先根 span
            summary.setUserId(rootSpan != null && rootSpan.getUserId() != null && !rootSpan.getUserId().isEmpty()
                    ? rootSpan.getUserId()
                    : firstNonNull(spans, SpanEventEntity::getUserId));
            // Prompt 关联（Langfuse prompt linkage）：从 span attributes 提取 prompt_name/prompt_version
            String[] promptMeta = extractPromptMeta(spans);
            summary.setPromptName(promptMeta[0]);
            summary.setPromptVersion(promptMeta[1]);
            // S1 聚合：LLM I/O 摘要 + 模型 + 成本
            aggregateAiFields(spans, summary);
            if (rootEnded) {
                summary.setCompletedAt(now);
            }
            try {
                traceSummaryMapper.insert(summary);
            } catch (DuplicateKeyException e) {
                // 并发场景：另一实例先建了 summary，降级为更新且不重复宣告完成
                TraceSummaryEntity winner = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, first.getWorkspaceId())
                        .eq(TraceSummaryEntity::getTraceId, first.getTraceId()));
                if (winner == null) {
                    return null;
                }
                updateSummary(winner, spans, errorCount, maxEnd, minStart, now, rootEnded, rootSpan);
                return null;
            }
            return rootEnded ? summary : null;
        }
        updateSummary(existing, spans, errorCount, maxEnd, minStart, now, rootEnded, rootSpan);
        // 收口判定回读 DB 状态：原子增量后本行 status/completed_at 才是权威。
        // 只在"本次更新把 RUNNING 收口为 COMPLETED"时返回 —— completed_at == 本轮 now（DB 毫秒截断后比对）
        if (rootEnded) {
            TraceSummaryEntity after = traceSummaryMapper.selectById(existing.getId());
            if (after != null && TraceSummaryEntity.STATUS_COMPLETED.equals(after.getStatus())
                    && after.getCompletedAt() != null
                    && !now.isAfter(after.getCompletedAt())
                    && after.getCompletedAt().isAfter(now.minusSeconds(5))) {
                return after;
            }
        }
        return null;
    }

    private void updateSummary(TraceSummaryEntity existing, List<SpanEventEntity> spans, long errorCount,
                               LocalDateTime maxEnd, LocalDateTime minStart, LocalDateTime now,
                               boolean rootEnded, SpanEventEntity rootSpan) {
        // 计数/时间窗/收口走原子增量：多实例或停机刷盘并发更新同一 trace 时，
        // 读-改-写会互相丢更新，且可能双双宣告 COMPLETED（重复 TRACE_COMPLETED 事件）。
        // 空字段回填（rootSpanId/session/user/prompt/AI 摘要）保留读-改-写：只填 null，幂等。
        traceSummaryMapper.incrementSummary(existing.getId(), spans.size(), (int) errorCount,
                minStart, maxEnd,
                rootSpan == null ? null : rootSpan.getSpanId(),
                firstNonNull(spans, SpanEventEntity::getSessionId),
                firstNonNull(spans, SpanEventEntity::getUserId),
                rootEnded, now);
        if (existing.getRootSpanId() == null && rootSpan != null) {
            existing.setRootSpanId(rootSpan.getSpanId());
        }
        if (existing.getSessionId() == null) {
            existing.setSessionId(firstNonNull(spans, SpanEventEntity::getSessionId));
        }
        if (existing.getUserId() == null) {
            existing.setUserId(firstNonNull(spans, SpanEventEntity::getUserId));
        }
        if (existing.getPromptName() == null) {
            String[] promptMeta = extractPromptMeta(spans);
            existing.setPromptName(promptMeta[0]);
            if (existing.getPromptVersion() == null) {
                existing.setPromptVersion(promptMeta[1]);
            }
        } else if (existing.getPromptVersion() == null) {
            String[] promptMeta = extractPromptMeta(spans);
            existing.setPromptVersion(promptMeta[1]);
        }
        aggregateAiFields(spans, existing);
        // 只回填"空字段回填 + AI 摘要"列：计数/时间窗/收口已由原子增量写入，
        // 整实体 updateById 会把旧值覆盖回去（多实例下又变回丢更新）
        traceSummaryMapper.update(null, Wrappers.<TraceSummaryEntity>lambdaUpdate()
                .eq(TraceSummaryEntity::getId, existing.getId())
                .set(TraceSummaryEntity::getRootSpanId, existing.getRootSpanId())
                .set(TraceSummaryEntity::getSessionId, existing.getSessionId())
                .set(TraceSummaryEntity::getUserId, existing.getUserId())
                .set(TraceSummaryEntity::getPromptName, existing.getPromptName())
                .set(TraceSummaryEntity::getPromptVersion, existing.getPromptVersion())
                .set(TraceSummaryEntity::getEnvironment, existing.getEnvironment())
                .set(TraceSummaryEntity::getModelName, existing.getModelName())
                .set(TraceSummaryEntity::getInputPreview, existing.getInputPreview())
                .set(TraceSummaryEntity::getOutputPreview, existing.getOutputPreview())
                .set(TraceSummaryEntity::getTotalCostUsd, existing.getTotalCostUsd()));
    }

    /**
     * sweeper 认领：把 idle 超时的 RUNNING trace 置为完成并返回本实例抢到的记录。
     * 认领走条件更新（affectedRows==1 才算抢到），多实例并发不重复。
     */
    public List<TraceSummaryEntity> sweepIdleTraces(long idleTimeoutMs, int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime idleBefore = now.minus(Duration.ofMillis(idleTimeoutMs));
        List<TraceSummaryEntity> stale = traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getStatus, TraceSummaryEntity.STATUS_RUNNING)
                .lt(TraceSummaryEntity::getLastSpanAt, idleBefore)
                .last("LIMIT " + Math.max(1, batchSize)));
        List<TraceSummaryEntity> claimed = new ArrayList<>();
        for (TraceSummaryEntity candidate : stale) {
            int won = traceSummaryMapper.completeIfIdle(candidate.getId(), idleBefore, now);
            if (won == 1) {
                claimed.add(traceSummaryMapper.selectById(candidate.getId()));
            }
        }
        return claimed;
    }

    /** 无显式成本上报时按模型价格（$/1M tokens）估算 LLM span 成本 */
    private void estimateCost(SpanEventEntity span, java.util.Map<String, ModelPriceEntity> priceCache) {
        if (!"llm".equals(span.getSpanType()) || span.getModelName() == null
                || (span.getCostUsd() != null && span.getCostUsd().signum() > 0)) {
            return;
        }
        ModelPriceEntity price = priceCache.computeIfAbsent(span.getModelName(), model ->
                modelPriceMapper.selectOne(Wrappers.<ModelPriceEntity>lambdaQuery()
                        .eq(ModelPriceEntity::getWorkspaceId, span.getWorkspaceId())
                        .eq(ModelPriceEntity::getModel, model)));
        if (price == null) {
            priceCache.put(span.getModelName(), null);
            return;
        }
        span.setCostUsd(computeCost(price, span.getInputTokens(), span.getOutputTokens(),
                span.getCachedInputTokens()));
    }

    /**
     * 按价格计算单条 LLM span 成本：未命中输入×输入价 + 命中缓存×缓存价 + 输出×输出价。
     * cached_input_per_million 未配置时按输入价 10%（OpenAI/DeepSeek 惯例）。
     */
    static java.math.BigDecimal computeCost(ModelPriceEntity price, Long inputTokens,
                                            Long outputTokens, Long cachedTokens) {
        long in = inputTokens == null ? 0 : inputTokens;
        long out = outputTokens == null ? 0 : outputTokens;
        long cached = cachedTokens == null ? 0 : Math.min(cachedTokens, in);
        java.math.BigDecimal cachedPrice = price.getCachedInputPerMillion() != null
                ? price.getCachedInputPerMillion()
                : price.getInputPerMillion().multiply(java.math.BigDecimal.valueOf(0.1))
                        .setScale(6, java.math.RoundingMode.HALF_UP);
        return price.getInputPerMillion()
                .multiply(java.math.BigDecimal.valueOf(in - cached))
                .add(cachedPrice.multiply(java.math.BigDecimal.valueOf(cached)))
                .add(price.getOutputPerMillion().multiply(java.math.BigDecimal.valueOf(out)))
                .divide(java.math.BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 价格新建/变更后重算该模型已有 llm span 的成本（估算只在首次 ingest 发生，
     * 后配的价格不会回溯旧数据，导致成本恒为 0）。
     *
     * @return 更新行数
     */
    public int reestimateCosts(String workspaceId, String model) {
        ModelPriceEntity price = modelPriceMapper.selectOne(Wrappers.<ModelPriceEntity>lambdaQuery()
                .eq(ModelPriceEntity::getWorkspaceId, workspaceId)
                .eq(ModelPriceEntity::getModel, model));
        if (price == null) {
            return 0;
        }
        int updated = 0;
        int pageNumber = 1;
        while (true) {
            SpanEventStore.SpanPage<SpanEventEntity> page = spanStore().search(
                    SpanEventStore.SpanQuery.builder()
                            .workspaceId(workspaceId).modelName(model).spanType("llm")
                            .pageNumber(pageNumber).pageSize(500)
                            .build());
            List<SpanEventEntity> batch = page.getRecords();
            if (batch.isEmpty()) {
                return updated;
            }
            for (SpanEventEntity span : batch) {
                if (span.getInputTokens() == null) {
                    continue;                       // 没带 token 的 llm span 无从计价
                }
                span.setCostUsd(computeCost(price, span.getInputTokens(), span.getOutputTokens(),
                        span.getCachedInputTokens()));
                updated++;
            }
            spanStore().updateCosts(batch);
            refreshSummaryCosts(workspaceId, batch);
            if (batch.size() < 500) {
                return updated;
            }
            pageNumber++;
        }
    }

    /** 重算后同步 trace 汇总的 totalCostUsd（汇总原是 ingest 时算的，span 成本变化后不变） */
    private void refreshSummaryCosts(String workspaceId, List<SpanEventEntity> spans) {
        java.util.Set<String> traceIds = new java.util.LinkedHashSet<>();
        for (SpanEventEntity s : spans) {
            traceIds.add(s.getTraceId());
        }
        for (String traceId : traceIds) {
            TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                    Wrappers.<TraceSummaryEntity>lambdaQuery()
                            .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                            .eq(TraceSummaryEntity::getTraceId, traceId));
            if (summary == null) {
                continue;
            }
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (SpanEventEntity s : spanStore().loadByTrace(workspaceId, traceId)) {
                if ("llm".equals(s.getSpanType()) && s.getCostUsd() != null) {
                    total = total.add(s.getCostUsd());
                }
            }
            // 只更新成本列：整实体回写会把并发的 status/计数覆盖回读到的旧值
            traceSummaryMapper.update(null, Wrappers.<TraceSummaryEntity>lambdaUpdate()
                    .eq(TraceSummaryEntity::getId, summary.getId())
                    .set(TraceSummaryEntity::getTotalCostUsd, total));
        }
    }

    /**
     * 从 span attributes 提取 prompt 元数据（prompt_name / prompt_version）。
     * SDK 上报 LLM span 时在 attributes 带上即建立 trace ↔ prompt 版本关联；缺失返回 {"", ""}。
     */
    private String[] extractPromptMeta(List<SpanEventEntity> spans) {
        for (SpanEventEntity span : spans) {
            String attrs = span.getAttributes();
            if (attrs == null || attrs.isEmpty() || !attrs.contains("prompt_name")) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(attrs);
                String name = node.path("prompt_name").asText(null);
                String version = node.path("prompt_version").asText(null);
                if ((name != null && !name.isEmpty()) || (version != null && !version.isEmpty())) {
                    return new String[]{
                            name == null ? "" : name,
                            version == null ? "" : version};
                }
            } catch (Exception ignored) {
                // attributes 非法 JSON 时跳过该 span
            }
        }
        return new String[]{"", ""};
    }

    /** 取 spans 中第一个非空字段值（优先顺序 = 传入顺序，根 span 由调用方排前） */
    private String firstNonNull(List<SpanEventEntity> spans, java.util.function.Function<SpanEventEntity, String> getter) {
        for (SpanEventEntity span : spans) {
            String v = getter.apply(span);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** 聚合 AI 字段到 summary（首条 LLM input 摘要 / 末条 LLM output 摘要 / 模型名 / 总成本） */
    private void aggregateAiFields(List<SpanEventEntity> spans, TraceSummaryEntity summary) {
        java.math.BigDecimal totalCost = java.math.BigDecimal.ZERO;
        if (summary.getEnvironment() == null && spans.get(0).getEnvironment() != null) {
            summary.setEnvironment(spans.get(0).getEnvironment());
        }
        for (SpanEventEntity span : spans) {
            if ("llm".equals(span.getSpanType()) && span.getCostUsd() != null) {
                totalCost = totalCost.add(span.getCostUsd());
            }
            if ("llm".equals(span.getSpanType())) {
                // LLM 数据权威：无条件覆盖（分批上报时后到的 llm span 会覆盖此前 fallback 或更早 llm 的值）
                if (span.getInputText() != null) {
                    summary.setInputPreview(truncatePreview(span.getInputText()));
                }
                if (span.getOutputText() != null) {
                    summary.setOutputPreview(truncatePreview(span.getOutputText()));
                }
                if (summary.getModelName() == null && span.getModelName() != null) {
                    summary.setModelName(span.getModelName());
                }
            }
        }
        if (totalCost.signum() > 0) {
            summary.setTotalCostUsd(totalCost);
        }
        fallbackPreviews(spans, summary);
    }

    /**
     * 无 LLM span 的 trace（CLI 桥接的工具型会话等）：回退取根 span（调用方保证排在首位）
     * 及其他 span 的输入/输出与模型名，保证列表摘要列可用。
     */
    private void fallbackPreviews(List<SpanEventEntity> spans, TraceSummaryEntity summary) {
        if (summary.getInputPreview() == null) {
            for (SpanEventEntity span : spans) {
                if (span.getInputText() != null && !span.getInputText().isEmpty()) {
                    summary.setInputPreview(truncatePreview(span.getInputText()));
                    break;
                }
            }
        }
        if (summary.getOutputPreview() == null) {
            for (int i = spans.size() - 1; i >= 0; i--) {
                String output = spans.get(i).getOutputText();
                if (output != null && !output.isEmpty()) {
                    summary.setOutputPreview(truncatePreview(output));
                    break;
                }
            }
        }
        if (summary.getModelName() == null) {
            summary.setModelName(firstNonNull(spans, SpanEventEntity::getModelName));
        }
    }

    private String truncatePreview(String text) {
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    public TraceSummaryEntity loadSummary(String workspaceId, String traceId) {
        return traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getTraceId, traceId));
    }

    public List<SpanEventEntity> loadSpans(String workspaceId, String traceId) {
        return spanStore().loadByTrace(workspaceId, traceId);
    }

    public List<TraceSummaryEntity> latestCompleted(String workspaceId, String agentKey, int limit) {
        return traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getAgentKey, agentKey)
                .eq(TraceSummaryEntity::getStatus, TraceSummaryEntity.STATUS_COMPLETED)
                .orderByDesc(TraceSummaryEntity::getStartTime)
                .last("LIMIT " + Math.max(1, limit)));
    }
}
