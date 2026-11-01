package io.emonlens.server.storage.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.IncidentEntity;

public interface IncidentMapper extends BaseMapper<IncidentEntity> {

    /** 状态机守卫式迁移：workspace_id 参与条件，防止跨租户按 id 改写状态。 */
    @Update("UPDATE et_incident SET status = #{target}, "
            + "open_fp = CASE WHEN #{target} = 'OPEN' THEN fingerprint ELSE NULL END, "
            + "updated_at = #{now} "
            + "WHERE id = #{id} AND workspace_id = #{workspaceId} AND status = #{expected}")
    int transition(@Param("id") String id, @Param("workspaceId") String workspaceId,
                   @Param("expected") String expected,
                   @Param("target") String target, @Param("now") LocalDateTime now);

    /** 原子累加命中次数并只升不降地抬升严重级，避免并发 trace 读-改-写互相覆盖。 */
    @Update("UPDATE et_incident SET "
            + "finding_count = COALESCE(finding_count, 0) + 1, "
            + "last_seen_at = #{now}, updated_at = #{now}, "
            + "severity = CASE WHEN #{rank} > CASE severity "
            + "    WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'WARNING' THEN 2 WHEN 'INFO' THEN 1 ELSE 0 END "
            + "  THEN #{severity} ELSE severity END "
            + "WHERE id = #{id} AND workspace_id = #{workspaceId}")
    int accumulate(@Param("id") String id, @Param("workspaceId") String workspaceId,
                   @Param("severity") String severity, @Param("rank") int rank,
                   @Param("now") LocalDateTime now);
}
