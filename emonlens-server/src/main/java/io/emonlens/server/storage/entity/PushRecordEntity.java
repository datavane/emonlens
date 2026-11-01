package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_push_record")
public class PushRecordEntity {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String eventId;

    private String eventType;

    private String targetUrl;

    private Integer attempts;

    private String lastStatus;

    private String lastResponse;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
