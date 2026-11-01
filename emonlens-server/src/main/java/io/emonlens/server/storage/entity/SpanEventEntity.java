package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_span_event")
public class SpanEventEntity {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String agentKey;

    private String agentVersion;

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String spanType;

    private String spanName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String status;

    private String errorType;

    private String modelName;

    private String toolName;

    private Long inputTokens;

    private Long outputTokens;

    /** 输入 tokens 中命中提示缓存的量 */
    private Long cachedInputTokens;

    /** JSON 字符串（内容采集本轮固定 NONE，仅承载长尾元数据） */
    private String sessionId;
    private String environment;

    /** 终端用户 ID（用户洞察聚合维度） */
    private String userId;

    private String inputText;

    private String outputText;

    private java.math.BigDecimal costUsd;

    private String level;

    private String attributes;

    private LocalDateTime ingestTime;
}
