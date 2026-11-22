package com.ithaka.batchinspector.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import com.ithaka.batchinspector.config.DataSourceRegistry.DbAccess;
import com.ithaka.batchinspector.dto.ExecutionSummary;
import com.ithaka.batchinspector.dto.ParameterDto;
import com.ithaka.batchinspector.dto.StepDto;
import com.ithaka.batchinspector.model.BatchStatus;
import com.ithaka.batchinspector.model.SearchCriteria;
import com.ithaka.batchinspector.model.Tables;

/**
 * SELECT-only access to the Spring Batch 5.x metadata tables.
 *
 * Schema assumptions (Spring Batch 5.x):
 *   JOB_INSTANCE(JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
 *   JOB_EXECUTION(JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED)
 *   JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING)
 *   STEP_EXECUTION(STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, COMMIT_COUNT, READ_COUNT, FILTER_COUNT,
 *                  WRITE_COUNT, READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT, ROLLBACK_COUNT, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED)
 *   JOB_EXECUTION_CONTEXT(JOB_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
 *   STEP_EXECUTION_CONTEXT(STEP_EXECUTION_ID, SHORT_CONTEXT, SERIALIZED_CONTEXT)
 *
 * All user input is bound. Only table names (validated identifiers) are concatenated.
 * Callers must invoke these methods inside DbAccess.tx() (read-only transaction).
 */
@Repository
public class BatchMetadataRepository {

    public static final int LIST_EXIT_MESSAGE_CHARS = 400;

    /** Filter-only WHERE fragment (status handled separately so counts ignore the status filter). */
    private record Where(String sql, MapSqlParameterSource params) {
    }

    // ------------------------------------------------------------------ search

    public Map<BatchStatus, Long> countByStatus(DbAccess a, SearchCriteria c) {
        Tables t = a.tables();
        Where w = filterWhere(t, c);
        String sql = "SELECT e.STATUS AS STATUS, COUNT(*) AS CNT FROM " + t.exec() + " e JOIN " + t.instance()
                + " i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID" + w.sql() + " GROUP BY e.STATUS";
        Map<BatchStatus, Long> counts = new EnumMap<>(BatchStatus.class);
        for (BatchStatus s : BatchStatus.values()) counts.put(s, 0L);
        a.jdbc().query(sql, w.params(), (RowCallbackAdapter) rs -> {
            BatchStatus s = BatchStatus.of(rs.getString("STATUS")); // unknown/future values fold into UNKNOWN
            counts.merge(s, rs.getLong("CNT"), Long::sum);
        });
        return counts;
    }

    public List<ExecutionSummary> search(DbAccess a, SearchCriteria c) {
        Tables t = a.tables();
        Where w = filterWhere(t, c);
        String statusSql = statusClause(c.status(), w.params());
        w.params().addValue("lim", c.limit());

        // Limit first, then compute step counts only for returned rows: no N+1 or full scan of steps.
        String orderByStart = a.dialect().nullsFirst("e.START_TIME") + ", e.JOB_EXECUTION_ID DESC";
        String orderedRows = "SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, " + displayJobName(t) + " AS JOB_NAME, e.VERSION, e.STATUS, e.CREATE_TIME, e.START_TIME,"
                + "          e.END_TIME, e.LAST_UPDATED, e.EXIT_CODE, SUBSTR(e.EXIT_MESSAGE, 1, " + LIST_EXIT_MESSAGE_CHARS + ") AS EXIT_MESSAGE"
                + "   FROM " + t.exec() + " e JOIN " + t.instance() + " i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID"
                + w.sql() + statusSql
                + "   ORDER BY " + orderByStart;
        String limitedRows = a.dialect().limit(orderedRows);
        String sql = "SELECT x.*, "
                + " (SELECT COUNT(*) FROM " + t.step() + " s WHERE s.JOB_EXECUTION_ID = x.JOB_EXECUTION_ID) AS STEP_COUNT,"
                + " (SELECT COUNT(*) FROM " + t.step() + " s WHERE s.JOB_EXECUTION_ID = x.JOB_EXECUTION_ID AND s.STATUS = 'FAILED') AS FAILED_STEP_COUNT"
                + " FROM (" + limitedRows + ") x"
                + " ORDER BY " + a.dialect().nullsFirst("x.START_TIME") + ", x.JOB_EXECUTION_ID DESC";
        return a.jdbc().query(sql, w.params(), EXECUTION_MAPPER);
    }

    // ------------------------------------------------------------------ detail

    public Optional<ExecutionSummary> findExecution(DbAccess a, long executionId) {
        Tables t = a.tables();
        String sql = "SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, " + displayJobName(t) + " AS JOB_NAME, e.VERSION, e.STATUS, e.CREATE_TIME, e.START_TIME,"
                + " e.END_TIME, e.LAST_UPDATED, e.EXIT_CODE, e.EXIT_MESSAGE, 0 AS STEP_COUNT, 0 AS FAILED_STEP_COUNT"
                + " FROM " + t.exec() + " e JOIN " + t.instance() + " i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID"
                + " WHERE e.JOB_EXECUTION_ID = :id";
        List<ExecutionSummary> r = a.jdbc().query(sql, new MapSqlParameterSource("id", executionId), EXECUTION_MAPPER);
        return r.stream().findFirst();
    }

    public String findJobKey(DbAccess a, long instanceId) {
        String sql = "SELECT JOB_KEY FROM " + a.tables().instance() + " WHERE JOB_INSTANCE_ID = :id";
        List<String> r = a.jdbc().query(sql, new MapSqlParameterSource("id", instanceId), (rs, i) -> rs.getString(1));
        return r.isEmpty() ? null : r.get(0);
    }

    public List<ParameterDto> findParameters(DbAccess a, long executionId) {
        String sql = "SELECT PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING FROM " + a.tables().params()
                + " WHERE JOB_EXECUTION_ID = :id ORDER BY PARAMETER_NAME";
        return a.jdbc().query(sql, new MapSqlParameterSource("id", executionId), (rs, i) -> new ParameterDto(
                rs.getString("PARAMETER_NAME"),
                rs.getString("PARAMETER_TYPE"),
                rs.getString("PARAMETER_VALUE"),
                "Y".equalsIgnoreCase(rs.getString("IDENTIFYING")),
                false));
    }

    public List<StepDto> findSteps(DbAccess a, long executionId) {
        String sql = "SELECT STEP_EXECUTION_ID, STEP_NAME, STATUS, VERSION, CREATE_TIME, START_TIME, END_TIME, LAST_UPDATED,"
                + " READ_COUNT, WRITE_COUNT, FILTER_COUNT, READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT,"
                + " COMMIT_COUNT, ROLLBACK_COUNT, EXIT_CODE, EXIT_MESSAGE FROM " + a.tables().step()
                + " WHERE JOB_EXECUTION_ID = :id ORDER BY " + a.dialect().nullsLast("START_TIME") + ", STEP_EXECUTION_ID";
        return a.jdbc().query(sql, new MapSqlParameterSource("id", executionId), (rs, i) -> new StepDto(
                rs.getLong("STEP_EXECUTION_ID"),
                rs.getString("STEP_NAME"),
                rs.getString("STATUS"),
                nullableLong(rs, "VERSION"),
                ts(rs, "CREATE_TIME"),
                ts(rs, "START_TIME"),
                ts(rs, "END_TIME"),
                ts(rs, "LAST_UPDATED"),
                nullableLong(rs, "READ_COUNT"),
                nullableLong(rs, "WRITE_COUNT"),
                nullableLong(rs, "FILTER_COUNT"),
                nullableLong(rs, "READ_SKIP_COUNT"),
                nullableLong(rs, "WRITE_SKIP_COUNT"),
                nullableLong(rs, "PROCESS_SKIP_COUNT"),
                nullableLong(rs, "COMMIT_COUNT"),
                nullableLong(rs, "ROLLBACK_COUNT"),
                rs.getString("EXIT_CODE"),
                rs.getString("EXIT_MESSAGE")));
    }

    /** Raw context text (not deserialised). Prefers SERIALIZED_CONTEXT, falls back to SHORT_CONTEXT. Capped at maxChars. */
    public record RawContext(long ownerId, String ownerName, String text, boolean truncated) {
    }

    public Optional<RawContext> findJobContext(DbAccess a, long executionId, int maxChars) {
        String sql = "SELECT SHORT_CONTEXT, SERIALIZED_CONTEXT FROM " + a.tables().execCtx() + " WHERE JOB_EXECUTION_ID = :id";
        List<RawContext> r = a.jdbc().query(sql, new MapSqlParameterSource("id", executionId),
                (rs, i) -> readContext(rs, executionId, "job", maxChars));
        return r.stream().findFirst();
    }

    public List<RawContext> findStepContexts(DbAccess a, long executionId, int maxChars) {
        Tables t = a.tables();
        String sql = "SELECT s.STEP_EXECUTION_ID, s.STEP_NAME, c.SHORT_CONTEXT, c.SERIALIZED_CONTEXT FROM " + t.step() + " s"
                + " JOIN " + t.stepCtx() + " c ON c.STEP_EXECUTION_ID = s.STEP_EXECUTION_ID"
                + " WHERE s.JOB_EXECUTION_ID = :id ORDER BY " + a.dialect().nullsLast("s.START_TIME") + ", s.STEP_EXECUTION_ID";
        return a.jdbc().query(sql, new MapSqlParameterSource("id", executionId),
                (rs, i) -> readContext(rs, rs.getLong("STEP_EXECUTION_ID"), rs.getString("STEP_NAME"), maxChars));
    }

    /** Cheap existence probe per table; returns the names of tables that cannot be read. */
    public List<String> probeTables(DbAccess a) {
        Tables t = a.tables();
        List<String> missing = new ArrayList<>();
        for (String table : List.of(t.instance(), t.exec(), t.params(), t.step(), t.execCtx(), t.stepCtx())) {
            try {
                a.jdbc().getJdbcTemplate().queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            } catch (RuntimeException ex) {
                missing.add(table);
            }
        }
        return missing;
    }

    public void ping(DbAccess a) {
        a.jdbc().getJdbcTemplate().queryForObject(a.dialect().pingSql(), Integer.class);
    }

    // ------------------------------------------------------------------ helpers

    private Where filterWhere(Tables t, SearchCriteria c) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (c.executionId() != null) {
            sb.append(" AND e.JOB_EXECUTION_ID = :execId");
            p.addValue("execId", c.executionId());
        } else {
            // Time filter is on the execution start time (falls back to create time for executions still STARTING).
            if (c.from() != null) {
                sb.append(" AND COALESCE(e.START_TIME, e.CREATE_TIME) >= :tFrom");
                p.addValue("tFrom", Timestamp.valueOf(c.from()));
            }
            if (c.to() != null) {
                sb.append(" AND COALESCE(e.START_TIME, e.CREATE_TIME) <= :tTo");
                p.addValue("tTo", Timestamp.valueOf(c.to()));
            }
        }
        if (c.instanceId() != null) {
            sb.append(" AND e.JOB_INSTANCE_ID = :instId");
            p.addValue("instId", c.instanceId());
        }
        if (c.jobNameContains() != null && !c.jobNameContains().isBlank()) {
            sb.append(" AND UPPER(").append(displayJobName(t)).append(") LIKE :jobName ESCAPE '!'");
            p.addValue("jobName", "%" + escapeLike(c.jobNameContains().trim().toUpperCase()) + "%");
        }
        return new Where(sb.toString(), p);
    }

    private String displayJobName(Tables t) {
        return "(SELECT MAX(p.PARAMETER_VALUE) FROM " + t.params()
                + " p WHERE p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID AND p.PARAMETER_NAME = 'JobName')";
    }

    private String statusClause(BatchStatus s, MapSqlParameterSource p) {
        if (s == null) return "";
        if (s == BatchStatus.UNKNOWN) {
            // UNKNOWN card = literal UNKNOWN + null + any value that is not a real Spring Batch status.
            StringBuilder sb = new StringBuilder(" AND (e.STATUS IS NULL OR e.STATUS NOT IN (");
            boolean first = true;
            for (BatchStatus k : BatchStatus.values()) {
                if (k == BatchStatus.UNKNOWN) continue;
                if (!first) sb.append(", ");
                sb.append(":k_").append(k.name());
                p.addValue("k_" + k.name(), k.name());
                first = false;
            }
            return sb.append("))").toString();
        }
        p.addValue("status", s.name());
        return " AND e.STATUS = :status";
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static RawContext readContext(ResultSet rs, long ownerId, String ownerName, int maxChars) throws SQLException {
        String text = null;
        boolean truncated = false;
        Object serialized = rs.getObject("SERIALIZED_CONTEXT");
        if (serialized instanceof java.sql.Clob clob) {
            long len = clob.length();
            if (len > 0) {
                int take = (int) Math.min(len, maxChars);
                text = clob.getSubString(1, take);
                truncated = len > maxChars;
            }
        } else if (serialized != null) {
            String serializedText = rs.getString("SERIALIZED_CONTEXT");
            if (serializedText != null && !serializedText.isEmpty()) {
                text = serializedText.length() > maxChars ? serializedText.substring(0, maxChars) : serializedText;
                truncated = serializedText.length() > maxChars;
            }
        }
        if (text == null) {
            String shortCtx = rs.getString("SHORT_CONTEXT");
            if (shortCtx != null) {
                text = shortCtx.length() > maxChars ? shortCtx.substring(0, maxChars) : shortCtx;
                truncated = shortCtx.length() > maxChars;
            }
        }
        return new RawContext(ownerId, ownerName, text, truncated);
    }

    private static final RowMapper<ExecutionSummary> EXECUTION_MAPPER = (rs, i) -> new ExecutionSummary(
            rs.getLong("JOB_EXECUTION_ID"),
            rs.getLong("JOB_INSTANCE_ID"),
            rs.getString("JOB_NAME"),
            rs.getString("STATUS"),
            nullableLong(rs, "VERSION"),
            ts(rs, "CREATE_TIME"),
            ts(rs, "START_TIME"),
            ts(rs, "END_TIME"),
            ts(rs, "LAST_UPDATED"),
            rs.getString("EXIT_CODE"),
            rs.getString("EXIT_MESSAGE"),
            rs.getInt("STEP_COUNT"),
            rs.getInt("FAILED_STEP_COUNT"));

    /** Tiny adapter so a lambda can be used as a RowCallbackHandler without ambiguity. */
    @FunctionalInterface
    private interface RowCallbackAdapter extends org.springframework.jdbc.core.RowCallbackHandler {
        void handle(ResultSet rs) throws SQLException;

        @Override
        default void processRow(ResultSet rs) throws SQLException {
            handle(rs);
        }
    }
}
