package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 评估器不可变版本快照：更新评估器不会覆盖历史 run 使用的 spec。 */
@Data
@TableName("et_evaluator_version")
public class EvaluatorVersionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String evaluatorId;
    private Integer version;
    private String type;
    private String specJson;
    private String fingerprint;
    private String templateKey;
    private Integer templateVersion;
    private String templateHash;
    private LocalDateTime createdAt;
}
