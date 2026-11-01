package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_annotation_queue_item")
public class AnnotationQueueItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String queueId;
    private String traceId;
    private String status;
    private String reviewer;
    private java.math.BigDecimal score;
    private String comment;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
