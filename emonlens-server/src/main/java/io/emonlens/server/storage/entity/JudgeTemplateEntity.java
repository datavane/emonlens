package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** workspace 自定义 Judge 模板版本；平台内置模板不落库，由代码注册表提供。 */
@Data
@TableName("et_judge_template")
public class JudgeTemplateEntity {

    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String templateKey;
    private Integer version;
    private String name;
    private String systemPrompt;
    private String userPrompt;
    private String outputSchema;
    private String status;
    private String fingerprint;
    private String createdBy;
    private LocalDateTime createdAt;
}
