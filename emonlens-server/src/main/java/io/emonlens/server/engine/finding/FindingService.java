package io.emonlens.server.engine.finding;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.Sha256Utils;
import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.engine.event.EventType;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.IncidentEntity;
import io.emonlens.server.storage.mapper.FindingMapper;
import io.emonlens.server.storage.mapper.IncidentMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Finding 落库 + Incident 自动归并。
 * fingerprint = sha256(tenant + agent + finding_type + detector)，同一指纹聚合到同一个 OPEN Incident。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingService {

    private final FindingMapper findingMapper;
    private final IncidentMapper incidentMapper;
    private final EventService eventService;
    private final ObjectMapper objectMapper;

    /** 事件 payload 序列化：agentKey/traceId 等来自上报数据，必须转义而不是字符串拼接。 */
    private String json(Map<String, Object> fields) {
        Map<String, Object> clean = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (v != null) {
                clean.put(k, v);
            }
        });
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Transactional
    public FindingEntity record(FindingEntity finding) {
        finding.setFingerprint(fingerprint(finding));
        if (finding.getCreatedAt() == null) {
            finding.setCreatedAt(LocalDateTime.now());
        }
        try {
            findingMapper.insert(finding);
        } catch (DuplicateKeyException e) {
            // 事件投递是 at-least-once：TRACE_COMPLETED 重试会重跑同一 trace 的检测。
            // uk_finding_occurrence(workspace_id, trace_id, type, detector_id) 兜底幂等——
            // 同一 trace 同一检测器的重复 Finding 直接吞掉，不重复并入 Incident、不重复触发评分/通知。
            log.info("duplicate finding suppressed (event retry), trace={}, type={}, detector={}",
                    finding.getTraceId(), finding.getType(), finding.getDetectorId());
            return null;
        }
        String incidentOutcome = mergeIncident(finding);
        // FINDING_CREATED 供通知/Webhook；SCORE_CHANGED 触发评分重算（fan-out 消费）
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("traceId", finding.getTraceId());
        fields.put("findingId", finding.getId());
        fields.put("agentKey", finding.getAgentKey());
        fields.put("severity", finding.getSeverity());
        String payload = json(fields);
        eventService.enqueue(EventType.FINDING_CREATED, finding.getWorkspaceId(),
                finding.getAgentKey(), payload);
        eventService.enqueue(EventType.SCORE_CHANGED, finding.getWorkspaceId(),
                finding.getAgentKey(), payload);
        // Incident 生命周期事件：创建 / 升级（escalated）单独回推
        if ("CREATED".equals(incidentOutcome)) {
            Map<String, Object> incidentFields = new LinkedHashMap<>();
            incidentFields.put("agentKey", finding.getAgentKey());
            incidentFields.put("severity", finding.getSeverity());
            incidentFields.put("status", "OPEN");
            eventService.enqueue(EventType.INCIDENT_CREATED, finding.getWorkspaceId(),
                    finding.getAgentKey(), json(incidentFields));
        } else if ("ESCALATED".equals(incidentOutcome)) {
            Map<String, Object> incidentFields = new LinkedHashMap<>();
            incidentFields.put("agentKey", finding.getAgentKey());
            incidentFields.put("severity", finding.getSeverity());
            incidentFields.put("status", "OPEN");
            incidentFields.put("escalatedTo", finding.getSeverity());
            eventService.enqueue(EventType.INCIDENT_ESCALATED, finding.getWorkspaceId(),
                    finding.getAgentKey(), json(incidentFields));
        }
        return finding;
    }

    private String mergeIncident(FindingEntity finding) {
        LocalDateTime now = LocalDateTime.now();
        IncidentEntity open = incidentMapper.selectOne(Wrappers.<IncidentEntity>lambdaQuery()
                .eq(IncidentEntity::getWorkspaceId, finding.getWorkspaceId())
                .eq(IncidentEntity::getAgentKey, finding.getAgentKey())
                .eq(IncidentEntity::getFingerprint, finding.getFingerprint())
                .eq(IncidentEntity::getStatus, IncidentEntity.STATUS_OPEN)
                .last("LIMIT 1"));
        if (open == null) {
            IncidentEntity incident = new IncidentEntity();
            incident.setWorkspaceId(finding.getWorkspaceId());
            incident.setAgentKey(finding.getAgentKey());
            incident.setFingerprint(finding.getFingerprint());
            incident.setOpenFp(finding.getFingerprint());
            incident.setTitle(finding.getType() + " on " + finding.getAgentKey());
            incident.setSeverity(finding.getSeverity());
            incident.setStatus(IncidentEntity.STATUS_OPEN);
            incident.setFindingCount(1);
            incident.setFirstSeenAt(now);
            incident.setLastSeenAt(now);
            incident.setCreatedAt(now);
            incident.setUpdatedAt(now);
            try {
                incidentMapper.insert(incident);
                return "CREATED";
            } catch (DuplicateKeyException e) {
                // 并发归并同一指纹：降级为累加到已存在的 OPEN Incident
                IncidentEntity winner = selectOpen(finding);
                if (winner != null) {
                    accumulate(winner, finding, now);
                    return "MERGED";
                }
                return "CREATED";
            }
        }
        boolean escalated = accumulate(open, finding, now);
        return escalated ? "ESCALATED" : "MERGED";
    }

    private IncidentEntity selectOpen(FindingEntity finding) {
        return incidentMapper.selectOne(Wrappers.<IncidentEntity>lambdaQuery()
                .eq(IncidentEntity::getWorkspaceId, finding.getWorkspaceId())
                .eq(IncidentEntity::getAgentKey, finding.getAgentKey())
                .eq(IncidentEntity::getFingerprint, finding.getFingerprint())
                .eq(IncidentEntity::getStatus, IncidentEntity.STATUS_OPEN)
                .last("LIMIT 1"));
    }

    private boolean accumulate(IncidentEntity incident, FindingEntity finding, LocalDateTime now) {
        String before = incident.getSeverity();
        // 原子增量 + 只升不降的 severity：并发 trace 归并同一 Incident 时不会互相覆盖计数/严重级
        incidentMapper.accumulate(incident.getId(), incident.getWorkspaceId(),
                finding.getSeverity(), severityRank(finding.getSeverity()), now);
        IncidentEntity after = incidentMapper.selectById(incident.getId());
        return after != null && severityRank(after.getSeverity()) > severityRank(before);
    }

    private String fingerprint(FindingEntity finding) {
        return Sha256Utils.hash(String.join("|",
                finding.getWorkspaceId(), finding.getAgentKey(), finding.getType(), finding.getDetectorId()));
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        switch (severity) {
            case "CRITICAL":
                return 4;
            case "HIGH":
                return 3;
            case "WARNING":
                return 2;
            case "INFO":
                return 1;
            default:
                return 0;
        }
    }
}
