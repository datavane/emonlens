package io.emonlens.server.storage.query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.emonlens.server.api.dto.PageVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.TraceTagEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.mapper.TraceTagMapper;

import lombok.RequiredArgsConstructor;

/**
 * trace 查询服务（控制面只读；未来平移到 StarRocks 查询仓储时仅替换本层实现）。
 */
@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private final TraceSummaryMapper traceSummaryMapper;
    private final TraceTagMapper traceTagMapper;

    public Page<TraceSummaryEntity> searchSummaries(String workspaceId, List<String> agentKeys, List<String> statuses,
                                                    LocalDateTime timeFrom, LocalDateTime timeTo,
                                                    Long minDurationMs, Long maxDurationMs, String userId,
                                                    String sessionId, Boolean hasError,
                                                    java.math.BigDecimal minCostUsd, java.math.BigDecimal maxCostUsd,
                                                    List<String> tags, List<String> environments,
                                                    List<String> modelNames, String promptName, String promptVersion, String query,
                                                    String sortBy, String sortOrder, int pageNumber, int pageSize) {
        LambdaQueryWrapper<TraceSummaryEntity> wrapper = Wrappers.lambdaQuery(TraceSummaryEntity.class);
        wrapper.eq(TraceSummaryEntity::getWorkspaceId, workspaceId);
        if (agentKeys != null && !agentKeys.isEmpty()) {
            wrapper.in(TraceSummaryEntity::getAgentKey, agentKeys);
        }
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(TraceSummaryEntity::getStatus, statuses);
        }
        if (timeFrom != null) {
            wrapper.ge(TraceSummaryEntity::getStartTime, timeFrom);
        }
        if (timeTo != null) {
            wrapper.le(TraceSummaryEntity::getStartTime, timeTo);
        }
        if (minDurationMs != null) {
            wrapper.ge(TraceSummaryEntity::getDurationMs, minDurationMs);
        }
        if (maxDurationMs != null) {
            wrapper.le(TraceSummaryEntity::getDurationMs, maxDurationMs);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getSessionId, sessionId);
        }
        if (Boolean.TRUE.equals(hasError)) {
            wrapper.gt(TraceSummaryEntity::getErrorCount, 0);
        }
        if (minCostUsd != null) {
            wrapper.ge(TraceSummaryEntity::getTotalCostUsd, minCostUsd);
        }
        if (maxCostUsd != null) {
            wrapper.le(TraceSummaryEntity::getTotalCostUsd, maxCostUsd);
        }
        if (userId != null && !userId.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getUserId, userId);
        }
        if (environments != null && !environments.isEmpty()) {
            wrapper.in(TraceSummaryEntity::getEnvironment, environments);
        }
        if (modelNames != null && !modelNames.isEmpty()) {
            wrapper.in(TraceSummaryEntity::getModelName, modelNames);
        }
        if (promptName != null && !promptName.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getPromptName, promptName);
        }
        if (promptVersion != null && !promptVersion.isEmpty()) {
            wrapper.eq(TraceSummaryEntity::getPromptVersion, promptVersion);
        }
        if (query != null && !query.isEmpty()) {
            String kw = escapeLike(query.trim());
            if (!kw.isEmpty()) {
                String pattern = "%" + kw + "%";
                // 用户输入里的 %/_ 必须转义，否则一个 "%" 会退化成全表扫描
                wrapper.and(w -> w.apply("input_preview LIKE {0} ESCAPE '!'", pattern)
                        .or().apply("output_preview LIKE {0} ESCAPE '!'", pattern)
                        .or().apply("trace_id LIKE {0} ESCAPE '!'", pattern));
            }
        }
        // 排序白名单（防止注入 / 非法列）
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String col = "durationMs".equals(sortBy) ? "duration_ms"
                : "totalCostUsd".equals(sortBy) ? "total_cost_usd"
                : "errorCount".equals(sortBy) ? "error_count" : "start_time";
        wrapper.last("ORDER BY " + col + (asc ? " ASC" : " DESC"));
        if (tags != null && !tags.isEmpty()) {
            // 标签→traceId 展开加上限，避免超大标签集生成巨型 IN 列表
            List<String> traceIds = traceTagMapper.selectList(Wrappers.<TraceTagEntity>lambdaQuery()
                            .eq(TraceTagEntity::getWorkspaceId, workspaceId)
                            .in(TraceTagEntity::getTag, tags)
                            .last("LIMIT 1000"))
                    .stream().map(TraceTagEntity::getTraceId).distinct().collect(Collectors.toList());
            if (traceIds.isEmpty()) {
                traceIds.add("__no_match__");
            }
            wrapper.in(TraceSummaryEntity::getTraceId, traceIds);
        }
        return traceSummaryMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
    }

    /** LIKE 通配符转义（以 ! 作为 ESCAPE，避免用户输入 %/_ 造成全表扫描）。 */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
