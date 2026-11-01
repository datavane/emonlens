package io.emonlens.server.api;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.auth.WorkspaceGuard;
import io.emonlens.server.storage.entity.IngestKeyEntity;
import io.emonlens.server.storage.mapper.IngestKeyMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Ingest Key 管理（补口 #27）：签发时明文仅返回一次，平台只保存 hash + 掩码。
 */
@RestController
@RequestMapping("/api/v1/ingest-keys")
@RequiredArgsConstructor
public class IngestKeyController {

    private final IngestKeyMapper ingestKeyMapper;

    @Data
    public static class IssueRequest {
        private String name;
    }

    @Data
    public static class IssuedVO {
        private String id;
        private String name;
        private String key;
        private String keyMasked;
        private String status;
        private LocalDateTime createdAt;
    }

    @PostMapping
    @RequiresPermission(Permissions.KEY_MANAGE)
    @Audit(action = "ingest-key.create", resourceType = "ingest-key")
    public ApiResult<IssuedVO> issue(@RequestBody(required = false) IssueRequest request) {
        String plain = "tk-" + random(12) + "-" + random(12) + "-" + random(4);
        IngestKeyEntity entity = new IngestKeyEntity();
        entity.setKeyHash(Sha256Utils.hash(plain));
        entity.setKeyMasked("tk-****-" + plain.substring(plain.length() - 4));
        if (request != null && request.getName() != null && !request.getName().isBlank()) {
            entity.setName(request.getName().trim());
        }
        entity.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        entity.setStatus(IngestKeyEntity.STATUS_ACTIVE);
        entity.setCreatedAt(LocalDateTime.now());
        ingestKeyMapper.insert(entity);
        IssuedVO vo = new IssuedVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setKey(plain);
        vo.setKeyMasked(entity.getKeyMasked());
        return ApiResult.ok(vo);
    }

    @GetMapping
    @RequiresPermission(Permissions.KEY_MANAGE)
    public ApiResult<List<IssuedVO>> list() {
        List<IngestKeyEntity> keys = ingestKeyMapper.selectList(Wrappers.<IngestKeyEntity>lambdaQuery()
                .eq(IngestKeyEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByDesc(IngestKeyEntity::getCreatedAt));
        return ApiResult.ok(keys.stream().map(k -> {
            IssuedVO vo = new IssuedVO();
            vo.setId(k.getId());
            vo.setName(k.getName());
            vo.setKeyMasked(k.getKeyMasked());
            vo.setStatus(k.getStatus());
            vo.setCreatedAt(k.getCreatedAt());
            return vo;
        }).collect(Collectors.toList()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.KEY_MANAGE)
    @Audit(action = "ingest-key.revoke", resourceType = "ingest-key")
    public ApiResult<Void> revoke(@PathVariable String id) {
        IngestKeyEntity key = ingestKeyMapper.selectById(id);
        if (key == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "ingest key not found");
        }
        WorkspaceGuard.owned(key.getWorkspaceId());
        key.setStatus(IngestKeyEntity.STATUS_REVOKED);
        ingestKeyMapper.updateById(key);
        return ApiResult.ok(null);
    }

    private static String random(int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
