package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_ingest_key")
public class IngestKeyEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String keyHash;

    private String keyMasked;

    /** 用途标签（如 prod-ingest / ci），签发时指定；列表可据此判断该吊销哪把 */
    private String name;

    private String workspaceId;


    private String status;

    private LocalDateTime createdAt;
}
