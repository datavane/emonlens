package io.emonlens.server.audit;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.AuditLogEntity;
import io.emonlens.server.storage.mapper.AuditLogMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping
    @RequiresPermission(Permissions.SYS_AUDIT_QUERY)
    public ApiResult<PageVO<AuditLogEntity>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String timeFrom,
            @RequestParam(required = false) String timeTo,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        LocalDateTime timeFromParsed = parseTime(timeFrom);
        LocalDateTime timeToParsed = parseTime(timeTo);
        LambdaQueryWrapper<AuditLogEntity> wrapper = Wrappers.lambdaQuery(AuditLogEntity.class)
                .eq(AuditLogEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .eq(username != null && !username.isEmpty(), AuditLogEntity::getUsername, username)
                .eq(action != null && !action.isEmpty(), AuditLogEntity::getAction, action)
                .ge(timeFromParsed != null, AuditLogEntity::getCreatedAt, timeFromParsed)
                .le(timeToParsed != null, AuditLogEntity::getCreatedAt, timeToParsed)
                .orderByDesc(AuditLogEntity::getCreatedAt);
        Page<AuditLogEntity> page = auditLogMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(e -> e).collect(Collectors.toList())));
    }

    /** 空或非法的时间筛选条件直接忽略（宽松过滤，不因查询参数报 500） */
    private LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Data
    public static class VO {
        private String id;
        private String username;
        private String action;
        private String resourceType;
        private String resourceId;
        private String detail;
        private String clientIp;
        private LocalDateTime createdAt;
    }
}
