package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_role")
public class RoleEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String code;

    private String name;

    private String remark;

    private Integer isBuiltin;
}
