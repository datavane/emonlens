package io.emonlens.server.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.validation.constraints.NotBlank;

import lombok.Data;

public final class MonitorDtos {

    private MonitorDtos() {
    }

    @Data
    public static class CreateRequest {

        /** 租户上下文；控制面直连时由请求体带入 */
        private String workspaceId = "ws-default";

        @NotBlank(message = "agentKey must not be blank")
        private String agentKey;

        @NotBlank(message = "monitorKey must not be blank")
        private String monitorKey;

        @NotBlank(message = "kind must not be blank")
        private String kind;

        /** Monitor DSL：与设计文档 §12 的 YAML 语义对齐，JSON 载体 */
        private Map<String, Object> spec;

        private String severity;
        private String dimension;
    }

    @Data
    public static class UpdateRequest {
        private Map<String, Object> spec;
        private String severity;
        private String dimension;
    }

    @Data
    public static class DryRunRequest {
        private int limit = 20;
    }

    @Data
    public static class VO {
        private String id;
        private String workspaceId;
        private String agentKey;
        private String monitorKey;
        private String kind;
        private Map<String, Object> spec;
        private String severity;
        private String dimension;
        private Integer enabled;
        private Integer version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class DryRunItemVO {
        private String traceId;
        private boolean matched;
        private List<String> violations;
    }
}
