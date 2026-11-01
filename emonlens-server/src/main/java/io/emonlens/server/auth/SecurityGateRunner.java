package io.emonlens.server.auth;

import io.emonlens.server.config.EmonLensProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.mapper.IngestKeyMapper;
import io.emonlens.server.storage.mapper.UserMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 生产启动门禁（security.mode=prod）：
 * 1) JWT 密钥必须配置、长度 >= 16，且不得是文档/示例里的占位值；
 * 2) admin 存在；若仍为 seed 默认密码，允许用 security.bootstrap-admin-password（环境变量注入）
 *    完成一次性轮换后放行，否则拒绝启动；bootstrap 密码同样拒绝占位值；
 * 3) ingest seed-key 不得为 dev 默认值或示例占位值，并自动禁用历史遗留的 dev 演示 key（升级库兜底）。
 */
@Slf4j
@Component
@Order(0)   // 必须先于 SeedKeyRunner：校验不过不执行任何数据写入
@RequiredArgsConstructor
public class SecurityGateRunner implements ApplicationRunner {

    static final String DEV_SEED_KEY = "dev-trust-key-001";
    private static final int MIN_JWT_SECRET_LENGTH = 16;

    private final EmonLensProperties properties;
    private final UserMapper userMapper;
    private final PwdService pwdService;
    private final IngestKeyMapper ingestKeyMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!"prod".equalsIgnoreCase(properties.getSecurity().getMode())) {
            log.warn("当前为 dev 模式（未设置 SPRING_PROFILES_ACTIVE=prod）：演示凭据 admin/123456、"
                    + "seed ingest key dev-trust-key-001、内置 JWT 密钥——仅供本地体验，切勿对外暴露");
            return;
        }
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.isEmpty()) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "prod 模式必须配置 emonlens.security.jwt-secret");
        }
        if (secret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "prod 模式 jwt-secret 长度不得少于 " + MIN_JWT_SECRET_LENGTH + " 字符");
        }
        if (isPlaceholder(secret)) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "prod 模式 jwt-secret 仍是示例占位值，请生成随机密钥（如 openssl rand -hex 32）");
        }
        if (DEV_SEED_KEY.equals(properties.getIngest().getSeedKey())) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "prod 模式禁止使用 dev 默认 ingest key（dev-trust-key-001），请配置 emonlens.ingest.seed-key");
        }
        if (properties.getIngest().getSeedKey() == null || properties.getIngest().getSeedKey().isEmpty()) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "prod 模式必须配置 emonlens.ingest.seed-key（数据面上报凭据）");
        }
        if (isPlaceholder(properties.getIngest().getSeedKey())) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                    "prod 模式 ingest seed-key 仍是示例占位值，请生成随机 key");
        }
        // seed SQL 内置的 dev 演示 key 在生产自动禁用（防开发凭据残留）
        int disabled = ingestKeyMapper.delete(Wrappers.<io.emonlens.server.storage.entity.IngestKeyEntity>lambdaQuery()
                .eq(io.emonlens.server.storage.entity.IngestKeyEntity::getKeyHash,
                        Sha256Utils.hash(DEV_SEED_KEY)));
        if (disabled > 0) {
            log.warn("prod 模式已自动禁用 dev 演示 ingest key（{} 行）", disabled);
        }
        UserEntity admin = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, "admin"));
        if (admin == null) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "prod 模式要求 admin 用户存在");
        }
        if (pwdService.verify("123456", admin.getPasswordHash())) {
            String bootstrap = properties.getSecurity().getBootstrapAdminPassword();
            if (bootstrap != null && bootstrap.length() >= 8) {
                if (isPlaceholder(bootstrap)) {
                    throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                            "prod 模式 bootstrap admin 密码仍是示例占位值，请设置随机的 EMONLENS_ADMIN_PASSWORD（>=8 位）");
                }
                admin.setPasswordHash(pwdService.hash(bootstrap));
                admin.setUpdatedAt(java.time.LocalDateTime.now());
                userMapper.updateById(admin);
                log.warn("admin 密码仍为 seed 默认值，已用 bootstrap-admin-password 完成首次轮换");
            } else {
                throw new EmonLensException(ErrorCode.INTERNAL_ERROR,
                        "prod 模式下 admin 仍为 seed 默认密码：请设置环境变量 EMONLENS_ADMIN_PASSWORD（>=8 位）"
                                + "以自动轮换，或手工修改后重启");
            }
        }
        log.info("security gate passed (prod mode)");
    }

    /** 文档/示例里常见的占位值：长度检查拦不住，必须在生产门禁显式拒绝。 */
    static boolean isPlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase(java.util.Locale.ROOT);
        return v.contains("change-me") || v.contains("changeme") || v.contains("replace-me")
                || v.contains("placeholder") || v.contains("your-secret") || v.contains("your-key")
                || v.contains("at-least-16-chars") || v.contains("prod-trust-key-change-me");
    }
}
