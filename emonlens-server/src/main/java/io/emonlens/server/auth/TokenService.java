package io.emonlens.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.TokenBlacklistEntity;
import io.emonlens.server.storage.mapper.TokenBlacklistMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 轻量 JWT（HS256，JDK 实现，零三方依赖）。
 * payload: {sub: userId, username, ws: workspaceId, exp: epochSeconds}
 * 吊销：logout 时按 token 摘要写入 et_auth_token_blacklist（DB 为准，多实例生效），parse 前置校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final TokenBlacklistMapper tokenBlacklistMapper;

    /** 过期黑名单行的惰性清理周期（10 分钟一次） */
    private final AtomicLong nextBlacklistSweepMs = new AtomicLong(0);

    public String issue(String userId, String username, String workspaceId, long ttlSeconds) {
        try {
            long exp = Instant.now().getEpochSecond() + ttlSeconds;
            String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = base64Url(objectMapper.writeValueAsBytes(Map.of(
                    "sub", userId, "username", username, "ws", workspaceId, "exp", exp)));
            String data = header + "." + payload;
            return data + "." + sign(data);
        } catch (Exception e) {
            throw new IllegalStateException("token issue failed", e);
        }
    }

    /** 校验签名与有效期与吊销状态，返回 claims；失败抛 40101 */
    public JsonNode parse(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "invalid token");
        }
        String data = parts[0] + "." + parts[1];
        if (!java.security.MessageDigest.isEqual(sign(data).getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "token signature mismatch");
        }
        try {
            JsonNode claims = objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
            if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
                throw new EmonLensException(ErrorCode.UNAUTHORIZED, "token expired");
            }
            if (isRevokedQuietly(token)) {
                throw new EmonLensException(ErrorCode.UNAUTHORIZED, "token revoked");
            }
            return claims;
        } catch (EmonLensException e) {
            throw e;
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "token unparseable");
        }
    }

    /** 登出吊销：记录 token 摘要与过期时间；过期行由惰性清理回收 */
    public void revoke(String token) {
        try {
            JsonNode claims = parse(token);
            TokenBlacklistEntity row = new TokenBlacklistEntity();
            row.setTokenHash(Sha256Utils.hash(token));
            row.setExpireAt(claims.path("exp").asLong());
            row.setCreatedAt(LocalDateTime.now());
            tokenBlacklistMapper.insert(row);
            sweepExpiredBlacklist();
        } catch (EmonLensException e) {
            throw e;
        } catch (Exception e) {
            log.warn("token revoke failed: {}", e.getMessage());
        }
    }

    private boolean isRevoked(String token) {
        return tokenBlacklistMapper.selectById(Sha256Utils.hash(token)) != null;
    }

    /** 黑名单查询失败（如 schema 未迁移）降级为不吊销并告警，认证链路不因此整体不可用 */
    private boolean isRevokedQuietly(String token) {
        try {
            return isRevoked(token);
        } catch (Exception e) {
            log.warn("token blacklist lookup failed, skip revocation check: {}", e.getMessage());
            return false;
        }
    }

    private void sweepExpiredBlacklist() {
        long now = System.currentTimeMillis();
        long next = nextBlacklistSweepMs.get();
        if (now - next < 600_000L || !nextBlacklistSweepMs.compareAndSet(next, now)) {
            return;
        }
        try {
            tokenBlacklistMapper.delete(Wrappers.<TokenBlacklistEntity>lambdaQuery()
                    .lt(TokenBlacklistEntity::getExpireAt, Instant.now().getEpochSecond()));
        } catch (Exception e) {
            log.warn("blacklist sweep failed: {}", e.getMessage());
        }
    }

    private String sign(String data) {
        try {
            Key key = new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return base64Url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private String secret() {
        String s = properties.getSecurity().getJwtSecret();
        return s == null || s.isEmpty() ? "emonlens-dev-jwt-secret" : s;
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
