package io.emonlens.server.storage.span;

import java.time.LocalDateTime;
import java.util.List;

import io.emonlens.server.storage.entity.SpanEventEntity;
import lombok.Builder;
import lombok.Data;

/**
 * Span 事件存储 SPI（插件化数据面）。
 *
 * <p>trace_summary / eval_result / finding 等治理面数据始终留在关系库；
 * span_event 的写入与读取全部经由本接口，按 emonlens.storage.mode 装配实现：
 * <ul>
 *   <li>mysql —— 内嵌 H2 / MySQL（默认，由 MyBatis-Plus 承载）</li>
 *   <li>starrocks —— MySQL 协议 JDBC 写读（DDL 见 sql/emonlens-starrocks.sql）</li>
 * </ul>
 * 新增存储目标：实现本接口并注册为 Spring Bean，在 {@link SpanEventStoreRegistry} 中按 kind 装配即可。
 */
public interface SpanEventStore {

    /** 实现标识，与 emonlens.storage.mode 取值对应（mysql / starrocks / ...） */
    String kind();

    /** LIKE 通配符转义（以 ! 作为 ESCAPE），防止用户输入 %/_ 退化为全表扫描。 */
    static String escapeLike(String value) {
        return value == null ? null
                : value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * 批量写入。实现必须幂等：同一 (workspace_id, trace_id, span_id) 重复写入时跳过该行、不抛错。
     *
     * @return 本轮实际新写入的行（重复行不返回），summary 聚合据此避免重复计数
     */
    InsertResult insertBatch(List<SpanEventEntity> spans);

    /** 写入结果：新写入行 + 跳过的重复行数 + 因字段超限/约束失败被单行隔离的行数 */
    class InsertResult {
        public final List<SpanEventEntity> written;
        public final int duplicates;
        public final int rejected;

        public InsertResult(List<SpanEventEntity> written, int duplicates) {
            this(written, duplicates, 0);
        }

        public InsertResult(List<SpanEventEntity> written, int duplicates, int rejected) {
            this.written = written;
            this.duplicates = duplicates;
            this.rejected = rejected;
        }
    }

    /** 按 trace 加载全部 span（start_time 升序），trace 详情/检测/评估共用读路径；必须按 workspace 隔离 */
    List<SpanEventEntity> loadByTrace(String workspaceId, String traceId);

    /** 按 trace 批量聚合 tokens：traceId -> [input, output, cached]（dashboard 按天 / 会话详情）。 */
    java.util.Map<String, long[]> sumTokensByTraceIds(String workspaceId, List<String> traceIds);

    /** 按 trace 统计 span 数：traceId -> count（summary 计数对账用）。 */
    java.util.Map<String, Long> countByTraceIds(String workspaceId, List<String> traceIds);

    /** 按模型聚合用量（模型价格页），每行含 modelName/spans/inputTokens/outputTokens/cachedInputTokens/costUsd。 */
    List<java.util.Map<String, Object>> sumUsageByModel(String workspaceId, LocalDateTime since);

    /**
     * 更新已有 span 的计价（价格新建/变更后重算历史成本）。
     * span 已由调用方按主键定位并填好新值，实现只需写回，返回写入行数。
     */
    int updateCosts(List<SpanEventEntity> spans);

    /** 条件查询：countOnly=true 时仅返回 total；否则按 start_time 排序返回一页 */
    SpanPage<SpanEventEntity> search(SpanQuery query);

    /** 保留清理：删除 ingestTime 早于 threshold 的行，返回删除行数（分批由实现负责） */
    int purgeBefore(LocalDateTime threshold, int batchSize);

    /** 查询条件（全等过滤 + 时间范围 + spanTypes IN + 分页排序） */
    @Data
    @Builder
    class SpanQuery {        private String workspaceId;
        private String agentKey;
        private String spanType;
        private String modelName;
        private String status;
        private String traceId;
        /** span 名称/工具名模糊匹配（Observations 搜索） */
        private String spanNameLike;
        /** 最小耗时 ms */
        private Long minDurationMs;
        private List<String> spanTypes;
        private LocalDateTime startTimeFrom;
        private LocalDateTime startTimeTo;
        private boolean orderDesc;
        private boolean countOnly;
        private int pageNumber;
        private int pageSize;
    }

    /** 统一返回结构 */
    @Data
    @Builder
    class SpanPage<T> {
        private List<T> records;
        private long total;

        public static <T> SpanPage<T> of(List<T> records, long total) {
            return SpanPage.<T>builder().records(records).total(total).build();
        }
    }
}
