package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** fan-out 事件中单个 handler 的完成账本（唯一键 event_id + handler_key）。 */
@Data
@TableName("et_event_handler_delivery")
public class EventHandlerDeliveryEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String eventId;

    private String handlerKey;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
