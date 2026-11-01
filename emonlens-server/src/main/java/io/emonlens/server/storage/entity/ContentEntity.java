package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_content")
public class ContentEntity {

    public static final String KIND_PROMPT = "prompt";
    public static final String KIND_COMPLETION = "completion";
    public static final String KIND_TOOL_PAYLOAD = "tool_payload";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String traceId;

    private String spanId;

    private String kind;

    private String captureMode;

    private String contentHash;

    private Long contentLength;

    private String preview;

    private String body;

    private LocalDateTime createdAt;
}
