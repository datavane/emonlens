package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.emonlens.common.EmonLensException;
import io.emonlens.server.auth.PwdService;
import io.emonlens.server.auth.SecurityGateRunner;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.mapper.IngestKeyMapper;
import io.emonlens.server.storage.mapper.UserMapper;

/**
 * 生产启动门禁：密钥/seed-key/admin 默认密码三项校验 + bootstrap 密码轮换 + dev key 自动禁用。
 */
class SecurityGateRunnerTest {

    private final PwdService pwdService = new PwdService();
    private final UserMapper userMapper = mock(UserMapper.class);
    private final IngestKeyMapper ingestKeyMapper = mock(IngestKeyMapper.class);

    private SecurityGateRunner runner(EmonLensProperties properties) {
        return new SecurityGateRunner(properties, userMapper, pwdService, ingestKeyMapper);
    }

    private EmonLensProperties props(String mode, String secret, String seedKey) {
        EmonLensProperties p = new EmonLensProperties();
        p.getSecurity().setMode(mode);
        p.getSecurity().setJwtSecret(secret);
        p.getIngest().setSeedKey(seedKey);
        return p;
    }

    @Test
    void devModeIsNoop() {
        EmonLensProperties p = props("dev", "", "dev-trust-key-001");
        runner(p).run(null);
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void prodRequiresJwtSecret() {
        SecurityGateRunner r = runner(props("prod", "", "real-key"));
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("jwt-secret");
    }

    @Test
    void prodRejectsShortJwtSecret() {
        SecurityGateRunner r = runner(props("prod", "short", "real-key"));
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("长度不得少于");
    }

    @Test
    void prodRejectsDevIngestKey() {
        SecurityGateRunner r = runner(props("prod", "a-long-enough-secret-value", "dev-trust-key-001"));
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("ingest key");
    }

    @Test
    void prodRejectsDefaultAdminPasswordWithoutBootstrap() {
        EmonLensProperties p = props("prod", "a-long-enough-secret-value", "real-key");
        when(userMapper.selectOne(any())).thenReturn(adminWithSeedPassword());
        assertThatThrownBy(() -> runner(p).run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("EMONLENS_ADMIN_PASSWORD");
        verify(userMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void prodRotatesAdminPasswordWithBootstrap() {
        EmonLensProperties p = props("prod", "a-long-enough-secret-value", "real-key");
        p.getSecurity().setBootstrapAdminPassword("new-strong-pass");
        when(userMapper.selectOne(any())).thenReturn(adminWithSeedPassword());

        runner(p).run(null);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(pwdService.verify("new-strong-pass", captor.getValue().getPasswordHash())).isTrue();
        // dev 演示 ingest key 同步禁用
        verify(ingestKeyMapper).delete(any());
    }

    @Test
    void prodRejectsPlaceholderJwtSecret() {
        SecurityGateRunner r = runner(props("prod", "change-me-to-a-random-string-at-least-16-chars", "real-key"));
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("示例占位值");
    }

    @Test
    void prodRejectsPlaceholderIngestKey() {
        SecurityGateRunner r = runner(props("prod", "a-long-enough-secret-value", "prod-trust-key-change-me"));
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("示例占位值");
    }

    @Test
    void prodRejectsPlaceholderBootstrapPassword() {
        EmonLensProperties p = props("prod", "a-long-enough-secret-value", "real-key");
        p.getSecurity().setBootstrapAdminPassword("change-me-admin-password");
        when(userMapper.selectOne(any())).thenReturn(adminWithSeedPassword());
        assertThatThrownBy(() -> runner(p).run(null))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("示例占位值");
        verify(userMapper, never()).updateById(any(UserEntity.class));
    }

    @Test
    void prodPassesWithConfiguredCredentials() {
        EmonLensProperties p = props("prod", "a-long-enough-secret-value", "real-key");
        UserEntity admin = adminWithSeedPassword();
        admin.setPasswordHash(pwdService.hash("already-rotated"));
        when(userMapper.selectOne(any())).thenReturn(admin);

        runner(p).run(null);

        verify(userMapper, never()).updateById(any(UserEntity.class));
        verify(ingestKeyMapper).delete(any());
    }

    private UserEntity adminWithSeedPassword() {
        UserEntity admin = new UserEntity();
        admin.setId("user-admin");
        admin.setUsername("admin");
        admin.setPasswordHash(pwdService.hash("123456"));
        return admin;
    }
}
