package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.AgentEntity;
import io.emonlens.server.storage.entity.AgentToolEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.AgentMapper;
import io.emonlens.server.storage.mapper.AgentToolMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Agent/Tool 主数据同步（补口 #28）：外部系统以 PUT 幂等同步。
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentMapper agentMapper;
    private final AgentToolMapper agentToolMapper;
    private final TraceSummaryMapper traceSummaryMapper;

    @Data
    public static class AgentSyncRequest {
        private String name;
        private String version;
        private String riskLevel;
        private String owner;
    }

    @Data
    public static class ToolSyncRequest {
        private String sideEffect;
    }

    @PutMapping("/{agentKey}")
    @RequiresPermission(Permissions.AGENT_MANAGE)
    @Audit(action = "agent.sync", resourceType = "agent")
    public ApiResult<Map<String, Object>> sync(@PathVariable String agentKey,
                                               @RequestBody AgentSyncRequest request) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        AgentEntity entity = agentMapper.selectOne(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getAgentKey, agentKey));
        LocalDateTime now = LocalDateTime.now();
        boolean isNew = entity == null;
        if (isNew) {
            entity = new AgentEntity();
            entity.setWorkspaceId(workspaceId);
            entity.setAgentKey(agentKey);
            entity.setCreatedAt(now);
        }
        entity.setName(request.getName() == null ? agentKey : request.getName());
        entity.setVersion(request.getVersion());
        entity.setRiskLevel(request.getRiskLevel() == null ? "MEDIUM" : request.getRiskLevel());
        entity.setOwner(request.getOwner());
        entity.setStatus(AgentEntity.STATUS_ACTIVE);
        entity.setUpdatedAt(now);
        if (isNew) {
            agentMapper.insert(entity);
        } else {
            agentMapper.updateById(entity);
        }
        return ApiResult.ok(toVo(entity, toolsOf(workspaceId, agentKey)));
    }

    @DeleteMapping("/{agentKey}")
    @RequiresPermission(Permissions.AGENT_MANAGE)
    @Audit(action = "agent.delete", resourceType = "agent")
    public ApiResult<Map<String, Object>> delete(@PathVariable String agentKey) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        AgentEntity entity = agentMapper.selectOne(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getAgentKey, agentKey));
        if (entity == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "agent not found: " + agentKey);
        }
        // 注册信息与工具清单一并移除；已上报的 Trace 历史保留（审计与回放依赖）
        int tools = agentToolMapper.delete(Wrappers.<AgentToolEntity>lambdaQuery()
                .eq(AgentToolEntity::getWorkspaceId, workspaceId)
                .eq(AgentToolEntity::getAgentKey, agentKey));
        agentMapper.deleteById(entity.getId());
        Map<String, Object> m = new HashMap<>();
        m.put("agentKey", agentKey);
        m.put("removedTools", tools);
        return ApiResult.ok(m);
    }

    @GetMapping
    @RequiresPermission(Permissions.AGENT_QUERY)
    public ApiResult<List<Map<String, Object>>> list() {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        List<AgentEntity> agents = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .orderByDesc(AgentEntity::getUpdatedAt));
        return ApiResult.ok(agents.stream()
                .map(a -> toVo(a, toolsOf(workspaceId, a.getAgentKey())))
                .collect(Collectors.toList()));
    }

    /** 接入健康检查（LangSmith project health 对标）：按 agent 聚合最近活动与 24h 调用/错误 */
    @GetMapping("/health")
    @RequiresPermission(Permissions.AGENT_QUERY)
    public ApiResult<List<Map<String, Object>>> health() {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .ge(TraceSummaryEntity::getStartTime, LocalDateTime.now().minusDays(30)));
        Map<String, List<TraceSummaryEntity>> byAgent = traces.stream()
                .collect(Collectors.groupingBy(t -> t.getAgentKey() == null ? "unknown" : t.getAgentKey()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<TraceSummaryEntity>> e : byAgent.entrySet()) {
            List<TraceSummaryEntity> ts = e.getValue();
            long traces24h = ts.stream().filter(t -> t.getStartTime() != null && t.getStartTime().isAfter(since)).count();
            long errors24h = ts.stream()
                    .filter(t -> t.getStartTime() != null && t.getStartTime().isAfter(since))
                    .filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();
            String lastSeen = ts.stream().map(t -> t.getLastSpanAt() != null ? t.getLastSpanAt() : t.getStartTime())
                    .filter(s -> s != null).max(Comparator.naturalOrder()).map(Object::toString).orElse("");
            Map<String, Object> m = new HashMap<>();
            m.put("agentKey", e.getKey());
            m.put("lastSeenAt", lastSeen);
            m.put("traces24h", traces24h);
            m.put("errors24h", errors24h);
            // 健康：24h 有流量且错误率 < 20% → healthy；有流量但错误率高 → degraded；30 天无流量 → idle
            if (ts.isEmpty() || (traces24h == 0 && ts.stream().noneMatch(t ->
                    t.getStartTime() != null && t.getStartTime().isAfter(LocalDateTime.now().minusDays(7))))) {
                m.put("health", "IDLE");
            } else {
                m.put("health", errors24h * 5 >= traces24h && traces24h > 0 ? "DEGRADED" : "HEALTHY");
            }
            rows.add(m);
        }
        rows.sort((a, b) -> String.valueOf(b.get("lastSeenAt")).compareTo(String.valueOf(a.get("lastSeenAt"))));
        return ApiResult.ok(rows);
    }

    @GetMapping("/{agentKey}")
    @RequiresPermission(Permissions.AGENT_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String agentKey) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        AgentEntity entity = agentMapper.selectOne(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getAgentKey, agentKey));
        if (entity == null) {
            return ApiResult.error(io.emonlens.common.ErrorCode.NOT_FOUND, "agent not found");
        }
        return ApiResult.ok(toVo(entity, toolsOf(workspaceId, agentKey)));
    }

    @PutMapping("/{agentKey}/tools/{toolName}")
    @RequiresPermission(Permissions.AGENT_MANAGE)
    @Audit(action = "agent.tool.sync", resourceType = "agent-tool")
    public ApiResult<Void> syncTool(@PathVariable String agentKey, @PathVariable String toolName,
                                    @RequestBody ToolSyncRequest request) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        AgentToolEntity existing = agentToolMapper.selectOne(Wrappers.<AgentToolEntity>lambdaQuery()
                .eq(AgentToolEntity::getWorkspaceId, workspaceId)
                .eq(AgentToolEntity::getAgentKey, agentKey)
                .eq(AgentToolEntity::getToolName, toolName));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AgentToolEntity tool = new AgentToolEntity();
            tool.setWorkspaceId(workspaceId);
            tool.setAgentKey(agentKey);
            tool.setToolName(toolName);
            tool.setSideEffect(request.getSideEffect() == null ? "WRITE" : request.getSideEffect());
            tool.setStatus(AgentToolEntity.STATUS_ACTIVE);
            tool.setCreatedAt(now);
            agentToolMapper.insert(tool);
        } else {
            existing.setSideEffect(request.getSideEffect() == null ? existing.getSideEffect() : request.getSideEffect());
            agentToolMapper.updateById(existing);
        }
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{agentKey}/tools/{toolName}")
    @RequiresPermission(Permissions.AGENT_MANAGE)
    @Audit(action = "agent.tool.remove", resourceType = "agent-tool")
    public ApiResult<Void> removeTool(@PathVariable String agentKey, @PathVariable String toolName) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        AgentToolEntity existing = agentToolMapper.selectOne(Wrappers.<AgentToolEntity>lambdaQuery()
                .eq(AgentToolEntity::getWorkspaceId, workspaceId)
                .eq(AgentToolEntity::getAgentKey, agentKey)
                .eq(AgentToolEntity::getToolName, toolName));
        if (existing != null) {
            existing.setStatus(AgentToolEntity.STATUS_INACTIVE);
            agentToolMapper.updateById(existing);
        }
        return ApiResult.ok(null);
    }

    private List<Map<String, Object>> toolsOf(String workspaceId, String agentKey) {
        return agentToolMapper.selectList(Wrappers.<AgentToolEntity>lambdaQuery()
                .eq(AgentToolEntity::getWorkspaceId, workspaceId)
                .eq(AgentToolEntity::getAgentKey, agentKey)
                .eq(AgentToolEntity::getStatus, AgentToolEntity.STATUS_ACTIVE))
                .stream().map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", t.getToolName());
                    m.put("sideEffect", t.getSideEffect());
                    return m;
                }).collect(Collectors.toList());
    }

    private Map<String, Object> toVo(AgentEntity entity, List<Map<String, Object>> tools) {
        Map<String, Object> vo = new HashMap<>();
        vo.put("agentKey", entity.getAgentKey());
        vo.put("name", entity.getName());
        vo.put("version", entity.getVersion());
        vo.put("riskLevel", entity.getRiskLevel());
        vo.put("owner", entity.getOwner());
        vo.put("status", entity.getStatus());
        vo.put("tools", tools);
        return vo;
    }
}
