package io.emonlens.server.api;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.server.storage.entity.ContentEntity;
import io.emonlens.server.storage.mapper.ContentMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.auth.CurrentContext;

/**
 * 采集内容查询：默认仅返回元信息（mode/hash/length/preview）；
 * includeBody=true 时返回正文（用户级内容权限由调用方 BFF 判定，V2.0 §3.3）。
 */
@RestController
@RequestMapping("/api/v1/traces/{traceId}/content")
@RequiresPermission(Permissions.TRACE_CONTENT)
@RequiredArgsConstructor
public class ContentController {

    private final ContentMapper contentMapper;

    @GetMapping
    public ApiResult<List<ContentVO>> list(@PathVariable String traceId,
                                           @RequestParam(required = false) String kind,
                                           @RequestParam(defaultValue = "false") boolean includeBody) {
        List<ContentEntity> contents = contentMapper.selectList(Wrappers.<ContentEntity>lambdaQuery()
                .eq(ContentEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .eq(ContentEntity::getTraceId, traceId)
                .eq(kind != null && !kind.isEmpty(), ContentEntity::getKind, kind)
                .orderByAsc(ContentEntity::getCreatedAt));
        return ApiResult.ok(contents.stream()
                .map(c -> toVo(c, includeBody)).collect(Collectors.toList()));
    }

    private ContentVO toVo(ContentEntity entity, boolean includeBody) {
        ContentVO vo = new ContentVO();
        vo.setId(entity.getId());
        vo.setSpanId(entity.getSpanId());
        vo.setKind(entity.getKind());
        vo.setCaptureMode(entity.getCaptureMode());
        vo.setContentHash(entity.getContentHash());
        vo.setContentLength(entity.getContentLength());
        vo.setPreview(entity.getPreview());
        vo.setBody(includeBody ? entity.getBody() : null);
        return vo;
    }

    @Data
    public static class ContentVO {
        private String id;
        private String spanId;
        private String kind;
        private String captureMode;
        private String contentHash;
        private Long contentLength;
        private String preview;
        private String body;
    }
}
