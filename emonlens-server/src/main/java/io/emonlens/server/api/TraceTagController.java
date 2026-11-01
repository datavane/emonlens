package io.emonlens.server.api;

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
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.TraceTagEntity;
import io.emonlens.server.storage.mapper.TraceTagMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** Trace 标签（对标 Langfuse Tags）：打标、去标、全工作空间标签枚举。 */
@RestController
@RequiredArgsConstructor
public class TraceTagController {

    /** 标签长度与单次打标数量上限：防超长/海量标签把标签枚举接口和 DB 拖垮 */
    private static final int MAX_TAG_CHARS = 128;
    private static final int MAX_TAGS_PER_REQUEST = 50;

    private final TraceTagMapper traceTagMapper;
    private final io.emonlens.server.storage.mapper.TraceSummaryMapper traceSummaryMapper;

    @Data
    public static class TagsRequest {
        private List<String> tags;
    }

    @GetMapping("/api/v1/environments")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<List<String>> distinctEnvironments() {
        String ws = CurrentContext.require().getWorkspaceId();
        return ApiResult.ok(traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, ws).isNotNull(TraceSummaryEntity::getEnvironment))
                .stream().map(TraceSummaryEntity::getEnvironment).filter(java.util.Objects::nonNull)
                .distinct().sorted().collect(Collectors.toList()));
    }

    @GetMapping("/api/v1/tags")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<List<String>> distinctTags() {
        String ws = CurrentContext.require().getWorkspaceId();
        return ApiResult.ok(traceTagMapper.selectList(Wrappers.<TraceTagEntity>lambdaQuery()
                        .eq(TraceTagEntity::getWorkspaceId, ws))
                .stream().map(TraceTagEntity::getTag).distinct().sorted().collect(Collectors.toList()));
    }

    @GetMapping("/api/v1/traces/{traceId}/tags")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<List<String>> tagsOf(@PathVariable String traceId) {
        String ws = CurrentContext.require().getWorkspaceId();
        return ApiResult.ok(traceTagMapper.selectList(Wrappers.<TraceTagEntity>lambdaQuery()
                        .eq(TraceTagEntity::getWorkspaceId, ws)
                        .eq(TraceTagEntity::getTraceId, traceId))
                .stream().map(TraceTagEntity::getTag).sorted().collect(Collectors.toList()));
    }

    @PostMapping("/api/v1/traces/{traceId}/tags")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "trace.tag", resourceType = "trace")
    public ApiResult<List<String>> addTags(@PathVariable String traceId, @RequestBody TagsRequest request) {
        String ws = CurrentContext.require().getWorkspaceId();
        java.util.List<String> incoming = request.getTags() == null ? List.of() : request.getTags();
        if (incoming.size() > MAX_TAGS_PER_REQUEST) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST,
                    "tags too many: " + incoming.size() + ", max " + MAX_TAGS_PER_REQUEST + " per request");
        }
        for (String tag : incoming) {
            String t = tag == null ? "" : tag.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() > MAX_TAG_CHARS) {
                throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST,
                        "tag too long: must be <= " + MAX_TAG_CHARS + " chars");
            }
            Long exists = traceTagMapper.selectCount(Wrappers.<TraceTagEntity>lambdaQuery()
                    .eq(TraceTagEntity::getWorkspaceId, ws)
                    .eq(TraceTagEntity::getTraceId, traceId)
                    .eq(TraceTagEntity::getTag, t));
            if (exists == 0) {
                TraceTagEntity entity = new TraceTagEntity();
                entity.setWorkspaceId(ws);
                entity.setTraceId(traceId);
                entity.setTag(t);
                entity.setCreatedAt(LocalDateTime.now());
                traceTagMapper.insert(entity);
            }
        }
        return tagsOf(traceId);
    }

    @DeleteMapping("/api/v1/traces/{traceId}/tags/{tag}")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "trace.untag", resourceType = "trace")
    public ApiResult<List<String>> removeTag(@PathVariable String traceId, @PathVariable String tag) {
        String ws = CurrentContext.require().getWorkspaceId();
        traceTagMapper.delete(Wrappers.<TraceTagEntity>lambdaQuery()
                .eq(TraceTagEntity::getWorkspaceId, ws)
                .eq(TraceTagEntity::getTraceId, traceId)
                .eq(TraceTagEntity::getTag, tag));
        return tagsOf(traceId);
    }
}
