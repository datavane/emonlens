package io.emonlens.server.engine.eval;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据集重放（Langfuse/LangSmith 实验语义）：把样本输入交给当前配置的 LLM 实际生成一次输出，
 * 并将这次调用落成一条真实 trace（root agent + llm span），随后即可复用全部既有评估器打分——
 * 使"改 prompt/换模型 → 重放数据集 → 对比通过率"的回归闭环成立。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetReplayService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final LlmClient llmClient;
    private final IngestService ingestService;
    private final TraceSummaryMapper traceSummaryMapper;

    @Value
    public static class ReplayResult {
        String traceId;
        String output;
        long latencyMs;
        String model;
        Integer promptTokens;
        Integer completionTokens;
    }

    /**
     * 重放一条样本：LLM 生成输出 → 上报为 trace（COMPLETED）→ 等待装配完成。
     * LLM 未配置抛 LlmUnavailableException（由调用方转 ERROR 行）。
     * 参考答案绝不会传入这里：生成请求只使用 input + systemPrompt + model。
     */
    public ReplayResult replay(String workspaceId, String agentKey, String input,
                               String systemPrompt, String model) {
        if (input == null || input.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "样本缺少 input，无法重放");
        }
        String sys = systemPrompt == null || systemPrompt.isBlank()
                ? "You are a helpful assistant." : systemPrompt;
        String mdl = model;
        if (mdl == null || mdl.isBlank()) {
            SysSettingService.EvalLlm cfg = llmClient.effectiveConfig();
            mdl = cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                    ? cfg.getModel() : "gpt-4o-mini";
        }

        long start = System.currentTimeMillis();
        LlmClient.ChatReply reply = llmClient.chatWithUsage(sys, input, mdl, 0.2);
        String output = reply.content();
        long latencyMs = System.currentTimeMillis() - start;

        String traceId = "replay-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime begin = end.minusNanos(latencyMs * 1_000_000L);
        String ak = agentKey == null || agentKey.isBlank() ? "dataset-replay" : agentKey;

        SpanEventDto root = base(traceId, "root", null, ak, "agent", "dataset_replay");
        root.setStartTime(begin.format(TS));
        root.setEndTime(end.format(TS));
        root.setInput(input);
        root.setOutput(abbreviate(output));

        SpanEventDto llm = base(traceId, "llm-1", "root", ak, "llm", "replay_turn");
        llm.setStartTime(begin.format(TS));
        llm.setEndTime(end.format(TS));
        llm.setModelName(mdl);
        llm.setInput(input);
        llm.setOutput(abbreviate(output));
        // 结构化内容（et_content）：REGEX/LLM_JUDGE 评估器从 content kind 读取，缺失会把重放判成 N/A
        java.util.Map<String, String> contents = new java.util.HashMap<>();
        contents.put("prompt", input);
        contents.put("completion", output == null ? "" : output);
        llm.setContent(contents);

        IngestResponse resp = ingestService.accept(new TrustContext("dataset-replay", workspaceId),
                List.of(llm, root));
        if (resp.getAcceptedCount() != 2) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "重放 trace 上报被拒绝: " + resp.getRejected());
        }
        awaitAssembled(traceId);
        return new ReplayResult(traceId, output, latencyMs, mdl,
                reply.promptTokens(), reply.completionTokens());
    }

    private SpanEventDto base(String traceId, String spanId, String parentSpanId,
                              String agentKey, String spanType, String spanName) {
        SpanEventDto dto = new SpanEventDto();
        dto.setTraceId(traceId);
        dto.setSpanId(spanId);
        dto.setParentSpanId(parentSpanId);
        dto.setAgentKey(agentKey);
        dto.setAgentVersion("replay");
        dto.setSpanType(spanType);
        dto.setSpanName(spanName);
        dto.setStatus(SpanEventDto.STATUS_OK);
        return dto;
    }

    /** span 批写为异步微批（约 200ms flush），评估前必须等 summary 就绪 */
    private void awaitAssembled(String traceId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                    Wrappers.<TraceSummaryEntity>lambdaQuery().eq(TraceSummaryEntity::getTraceId, traceId));
            if (summary != null) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "重放 trace 装配超时: " + traceId);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 8192 ? text.substring(0, 8192) : text;
    }
}
