package io.emonlens.server.api.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class FindingVO {

    private String id;
    private String agentKey;
    private String agentVersion;
    private String traceId;
    private String spanId;
    private String dimension;
    private String type;
    private String severity;
    private String detectorId;
    private String message;
    private String evidence;
    private LocalDateTime createdAt;
}
