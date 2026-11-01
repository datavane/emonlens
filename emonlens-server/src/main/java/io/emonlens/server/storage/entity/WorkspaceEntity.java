package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_workspace")
public class WorkspaceEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String code;

    private String name;

    private Integer isDefault;

    private LocalDateTime createdAt;
}
