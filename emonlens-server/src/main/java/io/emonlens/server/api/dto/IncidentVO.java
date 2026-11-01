package io.emonlens.server.api.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class IncidentVO {

    private String id;
    private String agentKey;
    private String title;
    private String severity;
    private String status;
    private Integer findingCount;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
