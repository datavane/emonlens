package io.emonlens.server.auth;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;

/**
 * 登录防爆破（实例内存态）：
 * 1) 单 IP 每分钟登录尝试上限（含成功，防凭据喷洒）；
 * 2) 同一 账号+IP 连续失败达到阈值后锁定一段时间，成功登录即清零。
 */
@Component
public class LoginGuard {

    private final EmonLensProperties properties;

    /** key = ip minuteBucket → 计数（过期条目惰性清理） */
    private final ConcurrentHashMap<String, AtomicLong> ipWindow = new ConcurrentHashMap<>();

    /** key = ip + "|" + username → 失败状态 */
    private final ConcurrentHashMap<String, FailState> failures = new ConcurrentHashMap<>();

    private final AtomicLong lastSweepMs = new AtomicLong(0);

    public LoginGuard(EmonLensProperties properties) {
        this.properties = properties;
    }

    /** 登录前置检查：锁定中或 IP 超限则抛 50301 */
    public void check(String ip, String username) {
        sweepIfDue();
        EmonLensProperties.Security sec = properties.getSecurity();
        FailState state = failures.get(key(ip, username));
        if (state != null && state.lockedUntilMs > Instant.now().toEpochMilli()) {
            throw new EmonLensException(ErrorCode.RATE_LIMITED,
                    "登录失败次数过多，账号暂时锁定，请稍后重试");
        }
        int ipLimit = sec.getLoginIpLimitPerMinute();
        if (ipLimit > 0) {
            String windowKey = ip + "@" + (System.currentTimeMillis() / 60000L);
            AtomicLong counter = ipWindow.computeIfAbsent(windowKey, k -> new AtomicLong());
            if (counter.incrementAndGet() > ipLimit) {
                throw new EmonLensException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁，请稍后重试");
            }
        }
    }

    public void onFailure(String ip, String username) {
        EmonLensProperties.Security sec = properties.getSecurity();
        int maxFailures = sec.getLoginMaxFailures();
        if (maxFailures <= 0) {
            return;
        }
        failures.compute(key(ip, username), (k, state) -> {
            if (state == null || state.expired(sec.getLoginLockMinutes())) {
                state = new FailState();
            }
            state.failCount++;
            if (state.failCount >= maxFailures) {
                state.lockedUntilMs = Instant.now().toEpochMilli() + sec.getLoginLockMinutes() * 60_000L;
            }
            return state;
        });
    }

    public void onSuccess(String ip, String username) {
        failures.remove(key(ip, username));
    }

    private String key(String ip, String username) {
        return ip + "|" + (username == null ? "" : username.toLowerCase());
    }

    /**
     * 客户端 IP：默认取 remoteAddr（不可伪造）；仅在可信反代后开启 trust-forwarded-for 才取 XFF 首段。
     */
    public static String clientIp(javax.servlet.http.HttpServletRequest request,
                                  EmonLensProperties properties) {
        if (properties.getSecurity().isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** 每 10 分钟惰性清理过期条目，防内存缓慢膨胀 */
    private void sweepIfDue() {
        long now = System.currentTimeMillis();
        long last = lastSweepMs.get();
        if (now - last < 600_000L || !lastSweepMs.compareAndSet(last, now)) {
            return;
        }
        long currentBucket = now / 60000L;
        ipWindow.keySet().removeIf(k -> {
            int idx = k.lastIndexOf('@');
            if (idx < 0) {
                return true;
            }
            try {
                return Long.parseLong(k.substring(idx + 1)) < currentBucket;
            } catch (NumberFormatException e) {
                return true;
            }
        });
        // 失败状态 24h 无活动即回收（锁定到期后仅由 check() 放行，残留计数无副作用）
        failures.values().removeIf(s -> now - s.updatedAtMs > 86_400_000L);
    }

    private static final class FailState {
        private int failCount;
        private long lockedUntilMs;
        private long updatedAtMs = System.currentTimeMillis();

        private boolean expired(int lockMinutes) {
            return System.currentTimeMillis() - updatedAtMs > Math.max(lockMinutes, 1) * 60_000L;
        }
    }
}
