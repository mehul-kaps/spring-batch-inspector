package com.ithaka.batchinspector.model;

import java.util.Locale;

import com.ithaka.batchinspector.exception.DashboardException;

/** SQL differences for the supported database engines. */
public enum DatabaseDialect {
    MYSQL("com.mysql.cj.jdbc.Driver"),
    ORACLE("oracle.jdbc.OracleDriver");

    private final String driverClassName;

    DatabaseDialect(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public static DatabaseDialect fromJdbcUrl(String jdbcUrl) {
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:mysql:")) return MYSQL;
        if (url.startsWith("jdbc:oracle:")) return ORACLE;
        throw new DashboardException(DashboardException.Kind.BAD_REQUEST,
                "Unsupported database URL. Use a MySQL or Oracle JDBC URL.");
    }

    public String limit(String orderedQuery) {
        return switch (this) {
            case MYSQL -> orderedQuery + " LIMIT :lim";
            case ORACLE -> "SELECT * FROM (" + orderedQuery + ") WHERE ROWNUM <= :lim";
        };
    }

    public String nullsFirst(String column) {
        return "CASE WHEN " + column + " IS NULL THEN 0 ELSE 1 END, " + column + " DESC";
    }

    public String nullsLast(String column) {
        return "CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END, " + column;
    }

    public String pingSql() {
        return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }
}
