package io.emonlens.server.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.storage.entity.PromptEntity;
import io.emonlens.server.storage.mapper.PromptMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;
    private final PromptMapper promptMapper;

    /**
     * Prompt 运行时拉取（Langfuse public get_prompt 对标）：数据面用 X-Trust-Key 鉴权，
     * 供 SDK get_prompt 按 label 解析版本（默认 production）。返回内容与控制面 /prompts/{name}/resolve 一致。
     */
    @GetMapping("/prompts/{name}/resolve")
    public ApiResult<Map<String, Object>> resolvePrompt(
            @RequestAttribute(IngestKeyFilter.TRUST_CONTEXT_ATTRIBUTE) TrustContext context,
            @PathVariable String name,
            @RequestParam(defaultValue = "production") String label) {
        PromptEntity hit = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                        .eq(PromptEntity::getWorkspaceId, context.getWorkspaceId())
                        .eq(PromptEntity::getName, name)
                        .orderByDesc(PromptEntity::getVersion))
                .stream()
                .filter(v -> {
                    String labels = v.getLabels() == null ? "" : v.getLabels();
                    if ("latest".equals(label)) {
                        return true; // 最高版本在 orderByDesc 首位
                    }
                    return java.util.Arrays.asList(labels.split(",")).contains(label);
                })
                .findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND,
                        "prompt not found: " + name + " @" + label));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", hit.getName());
        m.put("version", hit.getVersion());
        m.put("prompt", hit.getPrompt());
        m.put("config", hit.getConfigJson());
        m.put("labels", hit.getLabels() == null ? List.of() : List.of(hit.getLabels().split(",")));
        return ApiResult.ok(m);
    }

    @PostMapping("/events")
    public ApiResult<IngestResponse> ingest(
            @RequestAttribute(IngestKeyFilter.TRUST_CONTEXT_ATTRIBUTE) TrustContext context,
            @RequestBody List<SpanEventDto> spans) {
        if (spans == null || spans.isEmpty()) {
            return ApiResult.ok(new IngestResponse(0, Collections.emptyList(), 0, 0));
        }
        return ApiResult.ok(ingestService.accept(context, spans));
    }
}
