package io.emonlens.server.engine.monitor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.emonlens.server.api.dto.PageVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.detect.TrajectoryMatcher;
import io.emonlens.server.engine.detect.ValidationRule;
import io.emonlens.server.engine.detect.Violation;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.MonitorMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.emonlens.server.auth.WorkspaceGuard;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final MonitorMapper monitorMapper;
    private final TraceAssemblyService traceAssemblyService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    public MonitorEntity create(String workspaceId, String agentKey, String monitorKey,
                                String kind, Map<String, Object> spec, String severity, String dimension) {
        List<String> errors = validateSpec(kind, spec);
        if (!errors.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, String.join("; ", errors));
        }
        Long exists = monitorMapper.selectCount(Wrappers.<MonitorEntity>lambdaQuery()
                .eq(MonitorEntity::getWorkspaceId, workspaceId)
                .eq(MonitorEntity::getAgentKey, agentKey)
                .eq(MonitorEntity::getMonitorKey, monitorKey));
        if (exists != null && exists > 0) {
            throw new EmonLensException(ErrorCode.CONFLICT, "monitor_key already exists: " + monitorKey);
        }
        MonitorEntity entity = new MonitorEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setAgentKey(agentKey);
        entity.setMonitorKey(monitorKey);
        entity.setKind(kind);
        entity.setSpecJson(toJson(spec));
        entity.setSeverity(severity == null || severity.isEmpty() ? "HIGH" : severity);
        entity.setDimension(dimension == null || dimension.isEmpty() ? "OUTPUT" : dimension);
        entity.setEnabled(1);
        entity.setVersion(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        monitorMapper.insert(entity);
        return entity;
    }

    public MonitorEntity update(String id, String specJson, String severity, String dimension) {
        MonitorEntity entity = getById(id);
        if (specJson != null) {
            Map<String, Object> spec = fromJson(specJson);
            List<String> errors = validateSpec(entity.getKind(), spec);
            if (!errors.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, String.join("; ", errors));
            }
            entity.setSpecJson(toJson(spec));
        }
        if (severity != null) {
            entity.setSeverity(severity);
        }
        if (dimension != null) {
            entity.setDimension(dimension);
        }
        entity.setVersion(entity.getVersion() == null ? 2 : entity.getVersion() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        monitorMapper.updateById(entity);
        return entity;
    }

    public MonitorEntity getById(String id) {
        MonitorEntity entity = monitorMapper.selectById(id);
        if (entity == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "monitor not found: " + id);
        }
        WorkspaceGuard.owned(entity.getWorkspaceId());
        return entity;
    }

    public Page<MonitorEntity> page(String workspaceId, String agentKey, int pageNumber, int pageSize) {
        LambdaQueryWrapper<MonitorEntity> wrapper = Wrappers.lambdaQuery(MonitorEntity.class)
                .eq(MonitorEntity::getWorkspaceId, workspaceId);
        if (agentKey != null && !agentKey.isEmpty()) {
            wrapper.eq(MonitorEntity::getAgentKey, agentKey);
        }
        wrapper.orderByDesc(MonitorEntity::getCreatedAt);
        return monitorMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
    }

    public List<MonitorEntity> listEnabled(String workspaceId, String agentKey) {
        return monitorMapper.selectList(Wrappers.<MonitorEntity>lambdaQuery()
                .eq(MonitorEntity::getWorkspaceId, workspaceId)
                .eq(MonitorEntity::getAgentKey, agentKey)
                .eq(MonitorEntity::getEnabled, 1));
    }

    public MonitorEntity setEnabled(String id, boolean enabled) {
        MonitorEntity entity = getById(id);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now());
        monitorMapper.updateById(entity);
        return entity;
    }

    /** 规则语法校验：返回错误列表（空列表 = 通过） */
    public List<String> validateSpec(String kind, Map<String, Object> spec) {
        List<String> errors = new ArrayList<>();
        if (!MonitorEntity.KIND_VALIDATION.equals(kind) && !MonitorEntity.KIND_TRAJECTORY.equals(kind)
                && !MonitorEntity.KIND_EVALUATION.equals(kind) && !MonitorEntity.KIND_METRIC.equals(kind)) {
            errors.add("kind must be VALIDATION, TRAJECTORY, EVALUATION or METRIC");
            return errors;
        }
        if (MonitorEntity.KIND_METRIC.equals(kind)) {
            if (spec == null || spec.get("metric") == null || spec.get("thresholdValue") == null) {
                errors.add("metric monitor requires 'metric' and 'thresholdValue'");
            }
            return errors;
        }
        if (MonitorEntity.KIND_EVALUATION.equals(kind)) {
            if (spec == null || spec.get("evaluatorKey") == null
                    || String.valueOf(spec.get("evaluatorKey")).isEmpty()) {
                errors.add("evaluation monitor requires 'evaluatorKey'");
            }
            return errors;
        }
        MonitorSpec parsed = objectMapper.convertValue(spec, MonitorSpec.class);
        if (MonitorEntity.KIND_VALIDATION.equals(kind)) {
            if (parsed.getWhere() == null || parsed.getWhere().isEmpty()) {
                errors.add("validation spec requires 'where'");
            }
            return errors;
        }
        if (parsed.getRules() == null || parsed.getRules().isEmpty()) {
            errors.add("trajectory spec requires non-empty 'rules'");
            return errors;
        }
        for (MonitorSpec.RuleSpec rule : parsed.getRules()) {
            errors.addAll(validateRule(rule));
        }
        return errors;
    }

    private List<String> validateRule(MonitorSpec.RuleSpec rule) {
        List<String> errors = new ArrayList<>();
        String type = rule.getType() == null ? "" : rule.getType();
        switch (type) {
            case MonitorSpec.RuleSpec.TYPE_REQUIRES_BEFORE:
                if (rule.getAction() == null || rule.getRequired() == null || rule.getRequired().isEmpty()) {
                    errors.add("requires_before requires 'action' and non-empty 'required'");
                }
                break;
            case MonitorSpec.RuleSpec.TYPE_OCCURS_WITHOUT:
                if (rule.getAction() == null || rule.getForbidden() == null || rule.getForbidden().isEmpty()) {
                    errors.add("occurs_without requires 'action' and non-empty 'forbidden'");
                }
                break;
            case MonitorSpec.RuleSpec.TYPE_MAX_STEPS:
                if (rule.getLimit() == null || rule.getLimit() <= 0) {
                    errors.add("max_steps requires positive 'limit'");
                }
                break;
            case MonitorSpec.RuleSpec.TYPE_NO_CYCLE:
                break;
            default:
                errors.add("unknown rule type: " + rule.getType());
        }
        return errors;
    }

    public MonitorSpec parseSpec(MonitorEntity monitor) {
        try {
            return objectMapper.readValue(monitor.getSpecJson(), MonitorSpec.class);
        } catch (Exception e) {
            log.error("failed to parse monitor spec, id={}", monitor.getId(), e);
            return null;
        }
    }

    /**
     * dry-run：回放该 agent 最近完成的 N 条 trace，返回规则命中明细（发布前误报校验）。
     */
    public List<DryRunItem> dryRun(String id, int limit) {
        MonitorEntity monitor = getById(id);
        MonitorSpec spec = parseSpec(monitor);
        List<DryRunItem> items = new ArrayList<>();
        if (spec == null) {
            return items;
        }
        List<TraceSummaryEntity> traces = traceAssemblyService.latestCompleted(
                monitor.getWorkspaceId(), monitor.getAgentKey(), limit);
        for (TraceSummaryEntity trace : traces) {
            List<SpanEventEntity> spans = traceAssemblyService.loadSpans(
                    trace.getWorkspaceId(), trace.getTraceId());
            List<Violation> violations = MonitorEntity.KIND_VALIDATION.equals(monitor.getKind())
                    ? ValidationRule.evaluate(spans, spec)
                    : TrajectoryMatcher.evaluate(spans, spec);
            List<String> messages = new ArrayList<>();
            for (Violation violation : violations) {
                messages.add(violation.getMessage());
            }
            items.add(new DryRunItem(trace.getTraceId(), !violations.isEmpty(), messages));
        }
        return items;
    }

    private String toJson(Map<String, Object> spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec is not valid JSON object");
        }
    }

    private Map<String, Object> fromJson(String specJson) {
        try {
            return objectMapper.readValue(specJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec_json is not valid JSON object");
        }
    }

    @lombok.Value
    public static class DryRunItem {
        String traceId;
        boolean matched;
        List<String> violations;
    }

    /** 删除规则：历史 Incident/Finding 保留（审计轨迹），触达状态由 controller 级联清理。 */
    public void delete(String id) {
        monitorMapper.deleteById(id);
    }
}
