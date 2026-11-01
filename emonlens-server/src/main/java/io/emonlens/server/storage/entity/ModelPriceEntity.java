package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_model_price")
public class ModelPriceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String model;
    private java.math.BigDecimal inputPerMillion;
    private java.math.BigDecimal outputPerMillion;
    /** 缓存命中输入价（$/1M）；null=未配置，计价时回退为输入价的 10% */
    private java.math.BigDecimal cachedInputPerMillion;
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
