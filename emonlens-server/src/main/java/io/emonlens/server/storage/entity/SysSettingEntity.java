package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 系统级运行时设置（KV）。workspace_id 固定为 GLOBAL，setting_key 如 eval.llm。
 * 与 application.yml 的关系：DB 值优先，yml 作为兜底默认。
 */
@Data
@TableName("et_sys_setting")
public class SysSettingEntity {

    public static final String WORKSPACE_GLOBAL = "GLOBAL";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String settingKey;

    private String settingValue;

    private LocalDateTime updatedAt;
}
