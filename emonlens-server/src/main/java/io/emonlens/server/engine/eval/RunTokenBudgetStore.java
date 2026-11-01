package io.emonlens.server.engine.eval;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * run 级 token 预算（输入+输出）。
 *
 * <p>在每次外部 LLM 调用前按保守上界原子预留，调用后用 provider usage 结算；预留和结算都以
 * run 的 lease 为 fencing，旧 worker 不能动账本。预算不足时在发起 HTTP 之前失败，避免超卖。
 * usage 缺失时按预留额计入，宁可少跑不可少记。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunTokenBudgetStore {

    /** 预算生效时给 provider 的 completion 上限，预扣按此上界。 */
    public static final int MAX_OUTPUT_TOKENS = 1024;

    private final DatasetRunMapper datasetRunMapper;
    private final ThreadLocal<Budget> current = new ThreadLocal<>();

    public void bind(String runId, String leaseToken, Long maxTokens) {
        current.set(maxTokens == null ? null : new Budget(runId, leaseToken, maxTokens));
    }

    public void clear() {
        current.remove();
    }

    public boolean active() {
        return current.get() != null;
    }

    /** 预留一次调用的最坏 token 数；超出返回 false，由调用方在 HTTP 之前拒绝。 */
    public Reservation reserve(String systemPrompt, String userPrompt) {
        Budget budget = current.get();
        if (budget == null) {
            return Reservation.NONE;
        }
        long chars = (long) (systemPrompt == null ? 0 : systemPrompt.length())
                + (long) (userPrompt == null ? 0 : userPrompt.length());
        // 字符数 * 4 + 输出上界 + 消息开销：对 UTF-8 中英文均是保守上界。
        long estimate = chars * 4 + MAX_OUTPUT_TOKENS + 512;
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, budget.runId)
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .eq(DatasetRunEntity::getLeaseToken, budget.leaseToken)
                .isNotNull(DatasetRunEntity::getMaxTokens)
                .apply("tokens_reserved + tokens_spent + {0} <= max_tokens", estimate)
                .setSql("tokens_reserved = tokens_reserved + " + estimate));
        if (updated == 0) {
            throw new BudgetExceededException("token 预算不足：本次调用至少需要 " + estimate + " tokens");
        }
        return new Reservation(estimate, budget);
    }

    /** 按实际 usage 结算；usage 缺失时按预留额计，releaseUnused=false。 */
    public void settle(Reservation reservation, Integer promptTokens, Integer completionTokens) {
        if (reservation == null || reservation == Reservation.NONE || reservation.estimate <= 0) {
            return;
        }
        long actual = promptTokens == null || completionTokens == null
                ? reservation.estimate
                : Math.max(0, promptTokens.longValue() + completionTokens.longValue());
        // 预扣是上界；若 provider 回报更大，账本最多记到预扣额，保证硬上限不被击穿。
        actual = Math.min(actual, reservation.estimate);
        updateReservation(reservation, actual, true);
    }

    /** 调用失败/未产生 usage：释放预留，不计消费。 */
    public void release(Reservation reservation) {
        if (reservation == null || reservation == Reservation.NONE || reservation.estimate <= 0) {
            return;
        }
        updateReservation(reservation, 0, false);
    }

    private void updateReservation(Reservation reservation, long spent, boolean settle) {
        Budget budget = reservation.budget;
        var update = Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, budget.runId)
                .eq(DatasetRunEntity::getLeaseToken, budget.leaseToken)
                .setSql("tokens_reserved = CASE WHEN tokens_reserved >= " + reservation.estimate
                        + " THEN tokens_reserved - " + reservation.estimate + " ELSE 0 END");
        if (settle && spent > 0) {
            update.setSql("tokens_spent = tokens_spent + " + spent);
        }
        datasetRunMapper.update(null, update);
    }

    public static final class Reservation {
        static final Reservation NONE = new Reservation(0, null);
        final long estimate;
        final Budget budget;

        Reservation(long estimate, Budget budget) {
            this.estimate = estimate;
            this.budget = budget;
        }
    }

    private static final class Budget {
        final String runId;
        final String leaseToken;
        final Long maxTokens;

        Budget(String runId, String leaseToken, Long maxTokens) {
            this.runId = runId;
            this.leaseToken = leaseToken;
            this.maxTokens = maxTokens;
        }
    }

    public static class BudgetExceededException extends LlmUnavailableException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}
