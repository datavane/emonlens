package io.emonlens.server.engine.eval;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户可写 regex 的安全执行：长度限制 + 守护线程限时。
 * 灾难性回溯（如 (a+)+$）会挂死单一 event-poll 线程，进而冻结整条事件管线
 * （检测/评分/webhook 全部排队），配合实例崩溃还会产生滞留 CLAIMED 事件。
 * 超时的匹配按"不匹配"处理（label 由 requireMatch 语义决定），不中断事件循环。
 */
@Slf4j
final class SafeRegex {

    /** 模式长度上限：合法业务模式远短于此；超长直接拒绝编译 */
    static final int MAX_PATTERN_LENGTH = 256;
    /** 单次匹配预算：8KB 内容上正常匹配微秒级完成，1s 已是极宽裕上限 */
    private static final long MATCH_TIMEOUT_MS = 1_000;

    /**
     * 线程数固定：灾难性回溯无法被 interrupt 终止，超时的匹配线程会继续占用 CPU。
     * 有界池把失控线程总量封顶，避免 cached pool 无限膨胀拖垮实例；
     * 队列满时直接按“不匹配”降级，绝不阻塞事件消费线程。
     */
    private static final int POOL_SIZE = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService MATCH_POOL = new ThreadPoolExecutor(
            POOL_SIZE, POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "emonlens-regex-guard");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private SafeRegex() {
    }

    static Pattern compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "regex pattern required");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "regex pattern too long: " + pattern.length() + " > " + MAX_PATTERN_LENGTH);
        }
        try {
            return Pattern.compile(pattern, Pattern.DOTALL);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "invalid pattern: " + e.getMessage());
        }
    }

    /** 限时"包含"匹配（等价于原 matches("(?s).*pattern.*") 语义）；超时/异常视为不匹配 */
    static boolean matches(Pattern pattern, String body) {
        if (body == null) {
            return false;
        }
        java.util.concurrent.Future<Boolean> future;
        try {
            future = MATCH_POOL.submit((Callable<Boolean>) () ->
                    pattern.matcher(body).find());
        } catch (RejectedExecutionException e) {
            // 池/队列饱和（通常是前面的灾难性回溯占满了线程）：按不匹配降级，保护事件循环
            log.warn("regex match skipped, guard pool saturated");
            return false;
        }
        try {
            return future.get(MATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }
}
