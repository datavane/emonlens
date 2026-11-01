package io.emonlens.server.storage.span;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.SpanEventEntity;
import lombok.extern.slf4j.Slf4j;

/**
 * StarRocks 实现（MySQL 协议 JDBC）。配置 emonlens.storage.starrocks.jdbc-url 后注册，
 * 配合 emonlens.storage.mode=starrocks 激活；建表见 sql/emonlens-starrocks.sql（PRIMARY KEY 模型，
 * 主键 (workspace_id, trace_id, span_id) 保证重复写入幂等）。
 *
 * <p>写入：INSERT 批量提交（PRIMARY KEY 模型下重复主键自动覆盖，语义为幂等跳过）；
 * 读取：与关系实现同语义的 WHERE/ORDER/LIMIT；清理：DELETE 按 ingest_time（生产建议配合分区 TTL）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "emonlens.storage.starrocks", name = "jdbc-url")
public class StarRocksSpanEventStore implements SpanEventStore {

    private static final String INSERT_SQL = "INSERT INTO span_event ("
            + "workspace_id, trace_id, span_id, parent_span_id, agent_key, agent_version, span_type, span_name, "
            + "start_time, end_time, duration_ms, status, error_type, model_name, tool_name, "
            + "input_tokens, output_tokens, cost_usd, input_text, output_text, session_id, environment, "
            + "user_id, level, attributes, ingest_time, cached_input_tokens) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final EmonLensProperties.Starrocks config;
    private volatile HikariDataSource dataSource;

    public StarRocksSpanEventStore(EmonLensProperties properties) {
        this.config = properties.getStorage().getStarrocks();
    }

    @Override
    public String kind() {
        return "starrocks";
    }

    private Connection conn() throws SQLException {
        if (dataSource == null) {
            synchronized (this) {
                if (dataSource == null) {
                    HikariConfig hc = new HikariConfig();
                    hc.setJdbcUrl(config.getJdbcUrl());
                    hc.setUsername(config.getUsername());
                    hc.setPassword(config.getPassword());
                    hc.setMaximumPoolSize(Math.max(1, config.getMaxPoolSize()));
                    hc.setPoolName("emonlens-starrocks");
                    dataSource = new HikariDataSource(hc);
                }
            }
        }
        return dataSource.getConnection();
    }

    @Override
    public InsertResult insertBatch(List<SpanEventEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return new InsertResult(List.of(), 0);
        }
        List<SpanEventEntity> fresh;
        int duplicates = 0;
        try {
            fresh = filterNew(spans);
            duplicates = spans.size() - fresh.size();
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks span dedup check failed: " + e.getMessage(), e);
        }
        if (fresh.isEmpty()) {
            return new InsertResult(List.of(), duplicates);
        }
        int batchSize = Math.max(1, config.getWriteBatchSize());
        try (Connection c = conn()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
                int pending = 0;
                for (SpanEventEntity s : fresh) {
                    bind(ps, s);
                    ps.addBatch();
                    if (++pending >= batchSize) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks span insert failed: " + e.getMessage(), e);
        }
        return new InsertResult(fresh, duplicates);
    }

    /** PRIMARY KEY 覆盖写无法感知重复，写前按主键查重，保证与关系实现同语义（重复行不重复计数）。
     *  drainBatch 拉到的批混有多个 (workspace, trace)：按组分别查重，否则跨 trace 的 span
     *  会因条件不命中被误判为"新数据"，推高 summary 的 span_count/error_count/成本聚合。 */
    private List<SpanEventEntity> filterNew(List<SpanEventEntity> spans) throws SQLException {
        // 组内按 spanId 去重保序；主键是 (workspace_id, trace_id, span_id)
        Map<String, LinkedHashMap<String, SpanEventEntity>> groups = new LinkedHashMap<>();
        for (SpanEventEntity s : spans) {
            groups.computeIfAbsent(s.getWorkspaceId() + "|" + s.getTraceId(),
                            k -> new LinkedHashMap<>())
                    .put(s.getSpanId(), s);
        }
        List<SpanEventEntity> fresh = new ArrayList<>();
        int step = 500;
        try (Connection c = conn()) {
            for (Map.Entry<String, LinkedHashMap<String, SpanEventEntity>> group : groups.entrySet()) {
                String[] key = group.getKey().split("\\|", 2);
                Map<String, SpanEventEntity> byKey = group.getValue();
                List<String> ids = new ArrayList<>(byKey.keySet());
                for (int i = 0; i < ids.size(); i += step) {
                    List<String> chunk = ids.subList(i, Math.min(ids.size(), i + step));
                    String sql = "SELECT span_id FROM span_event WHERE workspace_id = ? AND trace_id = ? AND span_id IN ("
                            + placeholders(chunk.size()) + ")";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, key[0]);
                        ps.setString(2, key[1]);
                        for (int j = 0; j < chunk.size(); j++) {
                            ps.setString(j + 3, chunk.get(j));
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                byKey.remove(rs.getString(1));
                            }
                        }
                    }
                }
                fresh.addAll(byKey.values());
            }
        }
        return fresh;
    }

    @Override
    public List<SpanEventEntity> loadByTrace(String workspaceId, String traceId) {
        if (!hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        String sql = "SELECT " + COLUMNS + " FROM span_event WHERE workspace_id = ? AND trace_id = ? "
                + "ORDER BY start_time ASC";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks loadByTrace failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, long[]> sumTokensByTraceIds(String workspaceId, List<String> traceIds) {
        if (!hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Map<String, long[]> result = new java.util.HashMap<>();
        if (traceIds == null || traceIds.isEmpty()) {
            return result;
        }
        int step = 500;
        try (Connection c = conn()) {
            for (int i = 0; i < traceIds.size(); i += step) {
                List<String> chunk = traceIds.subList(i, Math.min(traceIds.size(), i + step));
                String sql = "SELECT trace_id, COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0), "
                        + "COALESCE(SUM(cached_input_tokens), 0) FROM span_event "
                        + "WHERE workspace_id = ? AND trace_id IN (" + placeholders(chunk.size())
                        + ") GROUP BY trace_id";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, workspaceId);
                    for (int j = 0; j < chunk.size(); j++) {
                        ps.setString(j + 2, chunk.get(j));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.put(rs.getString(1),
                                    new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks sumTokensByTraceIds failed: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public Map<String, Long> countByTraceIds(String workspaceId, List<String> traceIds) {
        if (!hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Map<String, Long> result = new java.util.HashMap<>();
        if (traceIds == null || traceIds.isEmpty()) {
            return result;
        }
        int step = 500;
        try (Connection c = conn()) {
            for (int i = 0; i < traceIds.size(); i += step) {
                List<String> chunk = traceIds.subList(i, Math.min(traceIds.size(), i + step));
                String sql = "SELECT trace_id, COUNT(*) AS cnt FROM span_event "
                        + "WHERE workspace_id = ? AND trace_id IN (" + placeholders(chunk.size())
                        + ") GROUP BY trace_id";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, workspaceId);
                    for (int j = 0; j < chunk.size(); j++) {
                        ps.setString(j + 2, chunk.get(j));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            result.put(rs.getString(1), rs.getLong(2));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks countByTraceIds failed: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> sumUsageByModel(String workspaceId, LocalDateTime since) {
        if (!hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        String sql = "SELECT model_name, COUNT(*) AS spans, COALESCE(SUM(input_tokens), 0), "
                + "COALESCE(SUM(output_tokens), 0), COALESCE(SUM(cached_input_tokens), 0), "
                + "COALESCE(SUM(cost_usd), 0) FROM span_event "
                + "WHERE workspace_id = ? AND model_name IS NOT NULL AND model_name != '' AND start_time >= ? "
                + "GROUP BY model_name";
        LocalDateTime from = since == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : since;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setTimestamp(2, Timestamp.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("modelName", rs.getString(1));
                    row.put("spans", rs.getLong(2));
                    row.put("inputTokens", rs.getLong(3));
                    row.put("outputTokens", rs.getLong(4));
                    row.put("cachedInputTokens", rs.getLong(5));
                    row.put("costUsd", rs.getBigDecimal(6));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks sumUsageByModel failed: " + e.getMessage(), e);
        }
        return rows;
    }

    @Override
    public SpanPage<SpanEventEntity> search(SpanQuery q) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (hasText(q.getWorkspaceId())) {
            where.append(" AND workspace_id = ?");
            args.add(q.getWorkspaceId());
        }
        if (hasText(q.getAgentKey())) {
            where.append(" AND agent_key = ?");
            args.add(q.getAgentKey());
        }
        if (hasText(q.getSpanType())) {
            where.append(" AND span_type = ?");
            args.add(q.getSpanType());
        }
        if (hasText(q.getModelName())) {
            where.append(" AND model_name = ?");
            args.add(q.getModelName());
        }
        if (hasText(q.getStatus())) {
            where.append(" AND status = ?");
            args.add(q.getStatus());
        }
        if (hasText(q.getTraceId())) {
            where.append(" AND trace_id = ?");
            args.add(q.getTraceId());
        }
        if (q.getSpanTypes() != null && !q.getSpanTypes().isEmpty()) {
            where.append(" AND span_type IN (").append(placeholders(q.getSpanTypes().size())).append(')');
            args.addAll(q.getSpanTypes());
        }
        if (q.getStartTimeFrom() != null) {
            where.append(" AND start_time >= ?");
            args.add(Timestamp.valueOf(q.getStartTimeFrom()));
        }
        if (q.getStartTimeTo() != null) {
            where.append(" AND start_time < ?");
            args.add(Timestamp.valueOf(q.getStartTimeTo()));
        }
        if (hasText(q.getSpanNameLike())) {
            where.append(" AND (span_name LIKE ? ESCAPE '!' OR tool_name LIKE ? ESCAPE '!')");
            String kw = "%" + SpanEventStore.escapeLike(q.getSpanNameLike().trim()) + "%";
            args.add(kw);
            args.add(kw);
        }
        if (q.getMinDurationMs() != null) {
            where.append(" AND duration_ms >= ?");
            args.add(q.getMinDurationMs());
        }
        try (Connection c = conn()) {
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM span_event" + where)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getLong(1);
                }
            }
            if (q.isCountOnly()) {
                return SpanPage.of(List.of(), total);
            }
            String sql = "SELECT " + COLUMNS + " FROM span_event" + where
                    + " ORDER BY start_time " + (q.isOrderDesc() ? "DESC" : "ASC")
                    + " LIMIT " + Math.max(1, q.getPageSize())
                    + " OFFSET " + Math.max(0, (Math.max(1, q.getPageNumber()) - 1) * Math.max(1, q.getPageSize()));
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return SpanPage.of(mapAll(rs), total);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks span search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int purgeBefore(LocalDateTime threshold, int batchSize) {
        int limit = Math.max(1, batchSize);
        try (Connection c = conn()) {
            // StarRocks DELETE 不支持 LIMIT：先取第 batchSize 行的 ingest_time 作为本轮截止点
            Timestamp cutoff = null;
            String probe = "SELECT ingest_time FROM span_event WHERE ingest_time < ? "
                    + "ORDER BY ingest_time ASC LIMIT " + limit;
            try (PreparedStatement ps = c.prepareStatement(probe)) {
                ps.setTimestamp(1, Timestamp.valueOf(threshold));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cutoff = rs.getTimestamp(1);
                    }
                }
            }
            if (cutoff == null) {
                return 0;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM span_event WHERE ingest_time <= ?")) {
                ps.setTimestamp(1, cutoff);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("starrocks purge failed (生产建议配置分区 TTL partition_live_number): {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 重算成本：主键模型下 INSERT 即覆盖写，复用全列 INSERT（调用方已把 span 全字段填好）。
     */
    @Override
    public int updateCosts(List<SpanEventEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        int batchSize = Math.max(1, config.getWriteBatchSize());
        try (Connection c = conn()) {
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
                int pending = 0;
                for (SpanEventEntity s : spans) {
                    bind(ps, s);
                    ps.addBatch();
                    if (++pending >= batchSize) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("starrocks span update failed: " + e.getMessage(), e);
        }
        return spans.size();
    }

    // ---------- 映射 ----------

    private static final String COLUMNS = "workspace_id, trace_id, span_id, parent_span_id, agent_key, agent_version, "
            + "span_type, span_name, start_time, end_time, duration_ms, status, error_type, model_name, tool_name, "
            + "input_tokens, output_tokens, cost_usd, input_text, output_text, session_id, environment, "
            + "user_id, level, attributes, ingest_time, cached_input_tokens";

    private void bind(PreparedStatement ps, SpanEventEntity s) throws SQLException {
        ps.setString(1, s.getWorkspaceId());
        ps.setString(2, s.getTraceId());
        ps.setString(3, s.getSpanId());
        ps.setString(4, s.getParentSpanId());
        ps.setString(5, s.getAgentKey());
        ps.setString(6, s.getAgentVersion());
        ps.setString(7, s.getSpanType());
        ps.setString(8, s.getSpanName());
        ps.setTimestamp(9, ts(s.getStartTime()));
        ps.setTimestamp(10, ts(s.getEndTime()));
        ps.setObject(11, s.getDurationMs());
        ps.setString(12, s.getStatus());
        ps.setString(13, s.getErrorType());
        ps.setString(14, s.getModelName());
        ps.setString(15, s.getToolName());
        ps.setObject(16, s.getInputTokens());
        ps.setObject(17, s.getOutputTokens());
        ps.setBigDecimal(18, s.getCostUsd());
        ps.setString(19, s.getInputText());
        ps.setString(20, s.getOutputText());
        ps.setString(21, s.getSessionId());
        ps.setString(22, s.getEnvironment());
        ps.setString(23, s.getUserId());
        ps.setString(24, s.getLevel());
        ps.setString(25, s.getAttributes());
        ps.setTimestamp(26, ts(s.getIngestTime()));
        ps.setObject(27, s.getCachedInputTokens());
    }

    private List<SpanEventEntity> mapAll(ResultSet rs) throws SQLException {
        List<SpanEventEntity> out = new ArrayList<>();
        while (rs.next()) {
            SpanEventEntity s = new SpanEventEntity();
            s.setWorkspaceId(rs.getString("workspace_id"));
            s.setTraceId(rs.getString("trace_id"));
            s.setSpanId(rs.getString("span_id"));
            s.setParentSpanId(rs.getString("parent_span_id"));
            s.setAgentKey(rs.getString("agent_key"));
            s.setAgentVersion(rs.getString("agent_version"));
            s.setSpanType(rs.getString("span_type"));
            s.setSpanName(rs.getString("span_name"));
            s.setStartTime(toLdt(rs.getTimestamp("start_time")));
            s.setEndTime(toLdt(rs.getTimestamp("end_time")));
            long dur = rs.getLong("duration_ms");
            s.setDurationMs(rs.wasNull() ? null : dur);
            s.setStatus(rs.getString("status"));
            s.setErrorType(rs.getString("error_type"));
            s.setModelName(rs.getString("model_name"));
            s.setToolName(rs.getString("tool_name"));
            long inTok = rs.getLong("input_tokens");
            s.setInputTokens(rs.wasNull() ? null : inTok);
            long outTok = rs.getLong("output_tokens");
            s.setOutputTokens(rs.wasNull() ? null : outTok);
            long cachedTok = rs.getLong("cached_input_tokens");
            s.setCachedInputTokens(rs.wasNull() ? null : cachedTok);
            BigDecimal cost = rs.getBigDecimal("cost_usd");
            s.setCostUsd(cost == null || cost.signum() == 0 ? null : cost);
            s.setInputText(rs.getString("input_text"));
            s.setOutputText(rs.getString("output_text"));
            s.setSessionId(rs.getString("session_id"));
            s.setEnvironment(rs.getString("environment"));
            s.setLevel(rs.getString("level"));
            s.setAttributes(rs.getString("attributes"));
            s.setIngestTime(toLdt(rs.getTimestamp("ingest_time")));
            out.add(s);
        }
        return out;
    }

    private String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    private void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    private Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private LocalDateTime toLdt(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
