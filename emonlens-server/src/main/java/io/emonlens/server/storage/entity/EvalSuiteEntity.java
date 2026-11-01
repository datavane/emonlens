package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/** 不可变 Eval Suite 版本：创建后只追加新版本，不覆盖历史 spec。 */
@Data
@TableName("et_eval_suite")
public class EvalSuiteEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String suiteKey;
    private String name;
    private String description;
    private Integer version;
    @JsonIgnore
    private String specJson;
    private String fingerprint;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private Map<String, Object> spec;
    @TableField(exist = false)
    private List<String> evaluatorKeys;
    @TableField(exist = false)
    private Integer evaluatorCount;
}
