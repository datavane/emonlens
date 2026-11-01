package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.List;

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
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.CommentEntity;
import io.emonlens.server.storage.mapper.CommentMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** Trace/Observation 评论（对标 Langfuse Comments）。 */
@RestController
@RequestMapping("/api/v1/traces/{traceId}/comments")
@RequiredArgsConstructor
public class CommentController {

    /** 评论长度上限：评论是全文存储，放大会拖累列表查询与 DB */
    private static final int MAX_CONTENT_CHARS = 10_000;

    private final CommentMapper commentMapper;

    @Data
    public static class CommentRequest {
        private String content;
    }

    @GetMapping
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<List<CommentEntity>> list(@PathVariable String traceId) {
        return ApiResult.ok(commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(CommentEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .eq(CommentEntity::getResourceType, "trace")
                .eq(CommentEntity::getResourceId, traceId)
                .orderByDesc(CommentEntity::getCreatedAt)));
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "trace.comment", resourceType = "trace")
    public ApiResult<CommentEntity> add(@PathVariable String traceId, @RequestBody CommentRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "content is required");
        }
        if (request.getContent().length() > MAX_CONTENT_CHARS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "content too long: must be <= " + MAX_CONTENT_CHARS + " chars");
        }
        CommentEntity entity = new CommentEntity();
        entity.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        entity.setResourceType("trace");
        entity.setResourceId(traceId);
        entity.setUsername(CurrentContext.require().getUsername());
        entity.setContent(request.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(entity);
        return ApiResult.ok(entity);
    }
}
