package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 已吊销（登出）的 JWT：token_hash 为原始 token 的 SHA-256，expire_at 为原 token 过期时间（epoch 秒）。 */
@Data
@TableName("et_auth_token_blacklist")
public class TokenBlacklistEntity {

    @TableId(type = IdType.INPUT)
    private String tokenHash;

    private Long expireAt;

    private LocalDateTime createdAt;
}
