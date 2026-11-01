package io.emonlens.server.storage.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.EventEntity;

public interface EventMapper extends BaseMapper<EventEntity> {

    /** 原子认领：仅当 affectedRows == 1 时本实例获得该事件的处理权。 */
    @Update("UPDATE et_event SET status = 'CLAIMED', claim_owner = #{owner}, claim_time = #{now} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int claimById(@Param("id") String id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    @Update("UPDATE et_event SET status = 'DONE' "
            + "WHERE id = #{id} AND status = 'CLAIMED' AND claim_owner = #{owner}")
    int completeById(@Param("id") String id, @Param("owner") String owner);

    /** 失败重回 PENDING 等待退避重试；nextRetryAt 由调用方按退避策略计算。 */
    @Update("UPDATE et_event SET status = 'PENDING', claim_owner = NULL, claim_time = NULL, "
            + "retry_count = #{retryCount}, next_retry_at = #{nextRetryAt} "
            + "WHERE id = #{id} AND status = 'CLAIMED' AND claim_owner = #{owner}")
    int requeueById(@Param("id") String id, @Param("owner") String owner,
                    @Param("retryCount") int retryCount, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("UPDATE et_event SET status = 'DEAD', claim_owner = NULL, claim_time = NULL, "
            + "retry_count = #{retryCount} "
            + "WHERE id = #{id} AND status = 'CLAIMED' AND claim_owner = #{owner}")
    int deadById(@Param("id") String id, @Param("owner") String owner, @Param("retryCount") int retryCount);

    /** 死信重投（人工恢复）：DEAD → PENDING，重试计数清零，任何实例下一轮即可认领 */
    @Update("UPDATE et_event SET status = 'PENDING', claim_owner = NULL, claim_time = NULL, "
            + "retry_count = 0, next_retry_at = NOW() "
            + "WHERE id = #{id} AND status = 'DEAD' AND workspace_id = #{workspaceId}")
    int requeueDeadById(@Param("id") String id, @Param("workspaceId") String workspaceId);

    /** 租约回收：认领后实例崩溃（kill -9/OOM）会永久滞留 CLAIMED，超时未完成的按原状态重回队列。
     *  回收也要累加 retry_count，避免“认领即崩溃”的毒事件无限循环；达到上限直接置 DEAD。 */
    @Update("UPDATE et_event SET "
            + "status = CASE WHEN COALESCE(retry_count, 0) + 1 >= #{maxRetry} THEN 'DEAD' ELSE 'PENDING' END, "
            + "claim_owner = NULL, claim_time = NULL, "
            + "retry_count = COALESCE(retry_count, 0) + 1, "
            + "next_retry_at = NOW() "
            + "WHERE status = 'CLAIMED' AND claim_time < #{leaseDeadline}")
    int reclaimExpiredLeases(@Param("leaseDeadline") LocalDateTime leaseDeadline,
                             @Param("maxRetry") int maxRetry);
}
