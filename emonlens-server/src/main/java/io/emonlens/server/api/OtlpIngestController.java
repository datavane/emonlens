package io.emonlens.server.api;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.api.IngestKeyFilter;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.otlp.OtlpJsonMapper;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;

import lombok.RequiredArgsConstructor;

/**
 * OTLP/HTTP-JSON trace 接入（OTel 规范 JSON 编码）：
 * POST /api/v1/ingest/otlp/v1/traces，body = ExportTraceServiceRequest JSON。
 * 归口 IngestKeyFilter 鉴权（数据面凭据），复用统一 ingest 管线（脱敏/采样/队列）。
 */
@RestController
@RequestMapping("/api/v1/ingest/otlp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "emonlens.otlp.enabled", havingValue = "true", matchIfMissing = true)
public class OtlpIngestController {

    private final OtlpJsonMapper otlpJsonMapper;
    private final IngestService ingestService;
    private final EmonLensProperties properties;

    @PostMapping(value = "/v1/traces", consumes = "application/json")
    public ApiResult<IngestResponse> ingest(
            @RequestAttribute(IngestKeyFilter.TRUST_CONTEXT_ATTRIBUTE) TrustContext context,
            @RequestBody String body) {
        // 解析前的防御性钳制：超大批次会在 JSON 解析期占满内存，先拒收并给出可操作的提示
        if (body != null && body.length() > properties.getOtlp().getMaxBodyChars()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "OTLP payload too large: " + body.length() + " chars, max "
                            + properties.getOtlp().getMaxBodyChars()
                            + "（请在 collector 侧减小 batch，或调大 emonlens.otlp.max-body-chars）");
        }
        List<SpanEventDto> spans = otlpJsonMapper.mapToSpanDtos(body);
        if (spans.isEmpty()) {
            return ApiResult.ok(new IngestResponse(0, java.util.Collections.emptyList(), 0, 0));
        }
        // Collector 批（默认 8192）远超单批上限：分块入队而不是整单 400
        return ApiResult.ok(ingestService.acceptChunked(context, spans));
    }
}
