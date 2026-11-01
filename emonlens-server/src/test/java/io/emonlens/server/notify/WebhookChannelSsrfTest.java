package io.emonlens.server.notify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 * Webhook SSRF 防护：默认拒绝回环/私网/链路本地目标，仅允许公网 http(s)。
 * （集成测试经 AbstractIntegrationTest 开 allow-private-webhook-targets 走本地 receiver。）
 */
class WebhookChannelSsrfTest {

    private final WebhookChannel channel = new WebhookChannel(
            new io.emonlens.server.config.EmonLensProperties());

    private void assertBlocked(String url) {
        assertThatThrownBy(() -> channel.assertSafeTarget(new URL(url)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void blocksLoopbackPrivateAndMetadataTargets() throws Exception {
        assertBlocked("http://127.0.0.1:8300/hook");            // 回环
        assertBlocked("http://localhost:8300/hook");            // 回环主机名
        assertBlocked("http://10.1.2.3/hook");                  // 私网 A 类
        assertBlocked("http://192.168.1.10/hook");              // 私网 C 类
        assertBlocked("http://172.16.0.9/hook");                // 私网 B 类
        assertBlocked("http://169.254.169.254/latest/meta-data"); // 云元数据（link-local）
        assertBlocked("http://100.64.0.1/hook");                // CGNAT 100.64/10
        assertBlocked("http://[fc00::1]/hook");                 // IPv6 ULA fc00::/7
        assertBlocked("file:///etc/passwd");                    // 非 http(s) 协议
    }

    @Test
    void allowsPublicHttpTargets() {
        // 用公网字面量避免测试依赖外部 DNS（CI/代理环境 DNS 可能超时）
        assertThatCode(() -> channel.assertSafeTarget(new URL("http://8.8.8.8/hook")))
                .doesNotThrowAnyException();
        assertThatCode(() -> channel.assertSafeTarget(new URL("http://1.1.1.1/hook")))
                .doesNotThrowAnyException();
    }
}
