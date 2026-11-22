package com.ithaka.batchinspector.config;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalised configuration ({@code dashboard.*}). */
@ConfigurationProperties(prefix = "dashboard")
public record DashboardProperties(
        @DefaultValue("BATCH_") String tablePrefix,
        String displayZone,
        @DefaultValue("50") int defaultMaxResults,
        @DefaultValue("500") int maxResultsLimit,
        @DefaultValue("20000") int maxContextChars,
        @DefaultValue("30") int queryTimeoutSeconds,
        @DefaultValue({"password", "passwd", "pwd", "secret", "token", "apikey", "api_key", "api-key",
                "credential", "private", "authorization"}) List<String> sensitiveParameterPatterns,
        String defaultDatabase,
        @DefaultValue List<Database> databases) {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    public ZoneId zone() {
        return (displayZone == null || displayZone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(displayZone.trim());
    }

    /** Databases that actually have a JDBC URL configured (blank slots are ignored). */
    public List<Database> configuredDatabases() {
        return databases.stream()
                .filter(d -> d.url() != null && !d.url().isBlank())
                .peek(d -> {
                    if (d.id() == null || !SAFE_ID.matcher(d.id()).matches()) {
                        throw new IllegalStateException("dashboard.databases[].id must match [A-Za-z0-9_-]{1,40}");
                    }
                })
                .toList();
    }

    public Optional<Database> find(String id) {
        if (id == null) return Optional.empty();
        return configuredDatabases().stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public Optional<Database> defaultDb() {
        List<Database> all = configuredDatabases();
        if (all.isEmpty()) return Optional.empty();
        if (defaultDatabase != null && !defaultDatabase.isBlank()) {
            Optional<Database> d = find(defaultDatabase.trim());
            if (d.isPresent()) return d;
        }
        return Optional.of(all.get(0));
    }

    public String prefixFor(Database db) {
        return (db.tablePrefix() != null && !db.tablePrefix().isBlank()) ? db.tablePrefix().trim() : tablePrefix;
    }

    /** One database holding Spring Batch metadata. */
    public record Database(
            String id,
            String name,
            /** Free text shown as a badge, e.g. DEV / QA / PROD. */
            String environment,
            String url,
            String username,
            String password,
            /** Overrides dashboard.table-prefix for this database. */
            String tablePrefix,
            /** Optional table owner (schema) when connecting as a different read-only user. */
            String schema,
            @DefaultValue Pool pool) {

        public String displayName() {
            return (name == null || name.isBlank()) ? id : name;
        }

        /** Never leak credentials through logs / actuator / debug output. */
        @Override
        public String toString() {
            return "Database[id=" + id + ", name=" + name + "]";
        }
    }

    public record Pool(
            @DefaultValue("4") int maxPoolSize,
            @DefaultValue("0") int minIdle,
            @DefaultValue("10000") long connectionTimeoutMs,
            @DefaultValue("60000") long idleTimeoutMs,
            @DefaultValue("600000") long maxLifetimeMs,
            @DefaultValue("5000") long validationTimeoutMs) {
    }
}
