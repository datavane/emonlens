package io.emonlens.server.storage.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.SpanEventEntity;

public interface SpanEventMapper extends BaseMapper<SpanEventEntity> {

    /** 按模型聚合 token/成本（模型价格页用量对照；轻量聚合列，避免拉全行） */
    @Select("SELECT model_name AS modelName, COUNT(*) AS spans, "
            + "COALESCE(SUM(input_tokens), 0) AS inputTokens, COALESCE(SUM(output_tokens), 0) AS outputTokens, "
            + "COALESCE(SUM(cached_input_tokens), 0) AS cachedInputTokens, "
            + "COALESCE(SUM(cost_usd), 0) AS costUsd "
            + "FROM et_span_event WHERE workspace_id = #{workspaceId} "
            + "AND model_name IS NOT NULL AND model_name != '' AND start_time >= #{since} "
            + "GROUP BY model_name")
    List<Map<String, Object>> sumUsageByModel(@Param("workspaceId") String workspaceId,
                                              @Param("since") LocalDateTime since);
}
