package io.emonlens.server.engine.incident;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.emonlens.server.api.dto.PageVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.engine.event.EventType;
import io.emonlens.server.storage.entity.IncidentEntity;
import io.emonlens.server.storage.mapper.IncidentMapper;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.auth.WorkspaceGuard;
import io.emonlens.server.auth.CurrentContext;

@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentMapper incidentMapper;
    private final EventService eventService;

    public IncidentEntity get(String id) {
        IncidentEntity incident = incidentMapper.selectById(id);
        if (incident == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "incident not found: " + id);
        }
        WorkspaceGuard.owned(incident.getWorkspaceId());
        return incident;
    }

    public Page<IncidentEntity> page(String agentKey, String status, int pageNumber, int pageSize) {
        LambdaQueryWrapper<IncidentEntity> wrapper = Wrappers.lambdaQuery(IncidentEntity.class)
                .eq(IncidentEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId());
        if (agentKey != null && !agentKey.isEmpty()) {
            wrapper.eq(IncidentEntity::getAgentKey, agentKey);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(IncidentEntity::getStatus, status);
        }
        wrapper.orderByDesc(IncidentEntity::getLastSeenAt);
        return incidentMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
    }

    @Transactional
    public IncidentEntity ack(String id) {
        // 先做归属校验，再做状态迁移：否则跨租户 id 会先被 UPDATE 再报 NOT_FOUND
        IncidentEntity incident = get(id);
        return transition(incident, IncidentEntity.STATUS_OPEN, IncidentEntity.STATUS_ACKNOWLEDGED);
    }

    @Transactional
    public IncidentEntity resolve(String id) {
        IncidentEntity incident = get(id);
        if (IncidentEntity.STATUS_ACKNOWLEDGED.equals(incident.getStatus())) {
            return transition(incident, IncidentEntity.STATUS_ACKNOWLEDGED, IncidentEntity.STATUS_RESOLVED);
        }
        return transition(incident, IncidentEntity.STATUS_OPEN, IncidentEntity.STATUS_RESOLVED);
    }

    private IncidentEntity transition(IncidentEntity incident, String expected, String target) {
        int updated = incidentMapper.transition(incident.getId(), incident.getWorkspaceId(),
                expected, target, LocalDateTime.now());
        if (updated != 1) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "incident " + incident.getId() + " is not in status " + expected);
        }
        IncidentEntity updatedIncident = get(incident.getId());
        String payload = "{\"incidentId\":\"" + incident.getId() + "\",\"agentKey\":\"" + updatedIncident.getAgentKey()
                + "\",\"status\":\"" + target + "\",\"severity\":\"" + updatedIncident.getSeverity() + "\"}";
        eventService.enqueue(EventType.INCIDENT_STATUS_CHANGED, updatedIncident.getWorkspaceId(),
                updatedIncident.getAgentKey(), payload);
        return updatedIncident;
    }

    /** 供查询侧按 trace 查 incident 归属（保留扩展） */
    public List<IncidentEntity> listByFingerprint(String workspaceId, String agentKey, String fingerprint) {
        return incidentMapper.selectList(Wrappers.<IncidentEntity>lambdaQuery()
                .eq(IncidentEntity::getWorkspaceId, workspaceId)
                .eq(IncidentEntity::getAgentKey, agentKey)
                .eq(IncidentEntity::getFingerprint, fingerprint));
    }
}
