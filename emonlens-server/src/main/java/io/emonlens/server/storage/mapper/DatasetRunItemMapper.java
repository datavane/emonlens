package io.emonlens.server.storage.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.DatasetRunItemEntity;

public interface DatasetRunItemMapper extends BaseMapper<DatasetRunItemEntity> {

    /**
     * Trust Score 只读证据：限定 workspace、COMPLETED run、agent 归属和时间窗。
     * agent_key 为空的历史 item 也返回，由适配器解析 snapshot 后二次过滤，兼容升级前数据。
     */
    @Select("SELECT ri.*, r.finished_at AS run_finished_at "
            + "FROM et_dataset_run_item ri JOIN et_dataset_run r ON r.id = ri.run_id "
            + "WHERE r.workspace_id = #{workspaceId} AND r.status = 'COMPLETED' "
            + "AND r.finished_at >= #{since} "
            + "AND (ri.agent_key = #{agentKey} OR ri.agent_key IS NULL) "
            + "ORDER BY r.finished_at DESC LIMIT 200")
    List<DatasetRunItemEntity> selectCompletedEvidence(@Param("workspaceId") String workspaceId,
                                                       @Param("agentKey") String agentKey,
                                                       @Param("since") LocalDateTime since);
}
