package io.emonlens.server.storage.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.query.SessionAggregate;

public interface TraceSummaryMapper extends BaseMapper<TraceSummaryEntity> {

    /**
     * 无中心化认领：条件更新把 idle 超时的 RUNNING trace 置为 COMPLETED。
     * 仅当 affectedRows == 1 时认定本实例抢到，才允许产生 TRACE_COMPLETED 事件。
     */
    @Update("UPDATE et_trace_summary SET status = 'COMPLETED', completed_at = #{completedAt} "
            + "WHERE id = #{id} AND status = 'RUNNING' AND last_span_at < #{idleBefore}")
    int completeIfIdle(@Param("id") String id,
                       @Param("idleBefore") LocalDateTime idleBefore,
                       @Param("completedAt") LocalDateTime completedAt);

    /**
     * 原子增量（替代读-改-写，防多实例/停机刷盘并发丢更新）：
     * 计数累加、时间窗扩张、空字段回填一次完成；rootEnded 时同时把 RUNNING 收口为 COMPLETED。
     * @return affectedRows（行存在即 1；是否本实例宣告完成由调用方回读 status/completed_at 判定）
     */
    @Update("UPDATE et_trace_summary SET "
            + "span_count = COALESCE(span_count, 0) + #{spanDelta}, "
            + "error_count = COALESCE(error_count, 0) + #{errorDelta}, "
            + "last_span_at = #{now}, "
            + "start_time = CASE WHEN start_time IS NULL OR #{minStart} < start_time THEN #{minStart} ELSE start_time END, "
            // 注意赋值顺序：MySQL 按从左到右求值。duration_ms 必须写在 end_time 之前，
            // 否则表达式里的 end_time 已是新值，条件恒为 false，duration_ms 永不更新。
            + "duration_ms = CASE WHEN #{maxEnd} IS NOT NULL AND (end_time IS NULL OR #{maxEnd} > end_time) "
            + "          THEN TIMESTAMPDIFF(MICROSECOND, "
            + "               CASE WHEN start_time IS NULL OR #{minStart} < start_time THEN #{minStart} ELSE start_time END, "
            + "               #{maxEnd}) / 1000 "
            + "          ELSE duration_ms END, "
            + "end_time = CASE WHEN #{maxEnd} IS NOT NULL AND (end_time IS NULL OR #{maxEnd} > end_time) "
            + "          THEN #{maxEnd} ELSE end_time END, "
            + "root_span_id = COALESCE(root_span_id, #{rootSpanId}), "
            + "session_id = COALESCE(session_id, #{sessionId}), "
            + "user_id = COALESCE(user_id, #{userId}), "
            + "completed_at = CASE WHEN status = 'RUNNING' AND #{rootEnded} THEN #{now} ELSE completed_at END, "
            + "status = CASE WHEN status = 'RUNNING' AND #{rootEnded} THEN 'COMPLETED' ELSE status END "
            + "WHERE id = #{id}")
    int incrementSummary(@Param("id") String id,
                         @Param("spanDelta") int spanDelta,
                         @Param("errorDelta") int errorDelta,
                         @Param("minStart") LocalDateTime minStart,
                         @Param("maxEnd") LocalDateTime maxEnd,
                         @Param("rootSpanId") String rootSpanId,
                         @Param("sessionId") String sessionId,
                         @Param("userId") String userId,
                         @Param("rootEnded") boolean rootEnded,
                         @Param("now") LocalDateTime now);

    /** 会话列表：数据库聚合 + 分页，避免把整个工作空间的 trace 拉进内存。 */
    @Select("<script>"
            + "SELECT s.session_id AS session_id, COUNT(*) AS trace_count, "
            + "COALESCE(SUM(s.error_count), 0) AS error_count, "
            + "COALESCE(SUM(s.total_cost_usd), 0) AS total_cost_usd, "
            + "COALESCE(SUM(s.duration_ms), 0) AS total_duration_ms, "
            + "MIN(s.start_time) AS first_seen, MAX(s.start_time) AS last_activity, "
            + "(SELECT t2.input_preview FROM et_trace_summary t2 "
            + " WHERE t2.workspace_id = #{workspaceId} AND t2.session_id = s.session_id "
            + " AND t2.input_preview IS NOT NULL AND t2.input_preview &lt;&gt; '' "
            + " ORDER BY t2.start_time ASC LIMIT 1) AS name "
            + "FROM et_trace_summary s "
            + "WHERE s.workspace_id = #{workspaceId} AND s.session_id IS NOT NULL AND s.session_id &lt;&gt; '' "
            + "<if test=\"timeFrom != null\"> AND s.start_time &gt;= #{timeFrom}</if>"
            + "<if test=\"agentKey != null and agentKey != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary a WHERE a.workspace_id = #{workspaceId} "
            + " AND a.session_id = s.session_id AND a.agent_key = #{agentKey})"
            + "</if>"
            + "<if test=\"environment != null and environment != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary e WHERE e.workspace_id = #{workspaceId} "
            + " AND e.session_id = s.session_id AND e.environment = #{environment})"
            + "</if>"
            + "<if test=\"userId != null and userId != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary u WHERE u.workspace_id = #{workspaceId} "
            + " AND u.session_id = s.session_id AND u.user_id = #{userId})"
            + "</if>"
            + "<if test=\"kwLike != null\">"
            + " AND (s.session_id LIKE #{kwLike} ESCAPE '!'"
            + " OR EXISTS (SELECT 1 FROM et_trace_summary u2 WHERE u2.workspace_id = #{workspaceId}"
            + "   AND u2.session_id = s.session_id AND u2.user_id LIKE #{kwLike} ESCAPE '!')"
            + " OR EXISTS (SELECT 1 FROM et_trace_summary p WHERE p.workspace_id = #{workspaceId}"
            + "   AND p.session_id = s.session_id AND p.input_preview LIKE #{kwLike} ESCAPE '!'))"
            + "</if>"
            + "GROUP BY s.session_id "
            + "<if test=\"hasError\"> HAVING COALESCE(SUM(s.error_count), 0) &gt; 0</if>"
            + " ORDER BY MAX(s.start_time) DESC LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<SessionAggregate> sessionPage(@Param("workspaceId") String workspaceId,
                                       @Param("agentKey") String agentKey,
                                       @Param("userId") String userId,
                                       @Param("environment") String environment,
                                       @Param("timeFrom") LocalDateTime timeFrom,
                                       @Param("kwLike") String kwLike,
                                       @Param("hasError") boolean hasError,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM (SELECT s.session_id FROM et_trace_summary s "
            + "WHERE s.workspace_id = #{workspaceId} AND s.session_id IS NOT NULL AND s.session_id &lt;&gt; '' "
            + "<if test=\"timeFrom != null\"> AND s.start_time &gt;= #{timeFrom}</if>"
            + "<if test=\"agentKey != null and agentKey != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary a WHERE a.workspace_id = #{workspaceId} "
            + " AND a.session_id = s.session_id AND a.agent_key = #{agentKey})"
            + "</if>"
            + "<if test=\"environment != null and environment != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary e WHERE e.workspace_id = #{workspaceId} "
            + " AND e.session_id = s.session_id AND e.environment = #{environment})"
            + "</if>"
            + "<if test=\"userId != null and userId != ''\">"
            + " AND EXISTS (SELECT 1 FROM et_trace_summary u WHERE u.workspace_id = #{workspaceId} "
            + " AND u.session_id = s.session_id AND u.user_id = #{userId})"
            + "</if>"
            + "<if test=\"kwLike != null\">"
            + " AND (s.session_id LIKE #{kwLike} ESCAPE '!'"
            + " OR EXISTS (SELECT 1 FROM et_trace_summary u2 WHERE u2.workspace_id = #{workspaceId}"
            + "   AND u2.session_id = s.session_id AND u2.user_id LIKE #{kwLike} ESCAPE '!')"
            + " OR EXISTS (SELECT 1 FROM et_trace_summary p WHERE p.workspace_id = #{workspaceId}"
            + "   AND p.session_id = s.session_id AND p.input_preview LIKE #{kwLike} ESCAPE '!'))"
            + "</if>"
            + "GROUP BY s.session_id "
            + "<if test=\"hasError\"> HAVING COALESCE(SUM(s.error_count), 0) &gt; 0</if>"
            + ") t</script>")
    long sessionCount(@Param("workspaceId") String workspaceId,
                      @Param("agentKey") String agentKey,
                      @Param("userId") String userId,
                      @Param("environment") String environment,
                      @Param("timeFrom") LocalDateTime timeFrom,
                      @Param("kwLike") String kwLike,
                      @Param("hasError") boolean hasError);

    @Select("<script>SELECT DISTINCT session_id, user_id FROM et_trace_summary "
            + "WHERE workspace_id = #{workspaceId} AND session_id IN "
            + "<foreach collection=\"sessionIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
            + " AND user_id IS NOT NULL AND user_id &lt;&gt; ''</script>")
    List<SessionAggregate> sessionUserLinks(@Param("workspaceId") String workspaceId,
                                            @Param("sessionIds") List<String> sessionIds);

    @Select("<script>SELECT DISTINCT session_id, agent_key FROM et_trace_summary "
            + "WHERE workspace_id = #{workspaceId} AND session_id IN "
            + "<foreach collection=\"sessionIds\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
            + " AND agent_key IS NOT NULL AND agent_key &lt;&gt; ''</script>")
    List<SessionAggregate> sessionAgentLinks(@Param("workspaceId") String workspaceId,
                                             @Param("sessionIds") List<String> sessionIds);
}
