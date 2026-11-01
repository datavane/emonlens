package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_menu")
public class MenuEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String parentId;

    private String name;

    private String path;

    private String icon;

    private Integer sort;

    private String type;

    private String permission;
}
