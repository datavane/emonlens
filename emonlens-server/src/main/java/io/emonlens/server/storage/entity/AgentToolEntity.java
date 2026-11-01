package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_agent_tool")
public class AgentToolEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String agentKey;

    private String toolName;

    private String sideEffect;

    private String status;

    private LocalDateTime createdAt;
}
