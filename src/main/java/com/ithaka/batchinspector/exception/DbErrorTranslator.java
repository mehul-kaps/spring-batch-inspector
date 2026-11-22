package com.ithaka.batchinspector.exception;

import java.sql.SQLException;

/** Converts low-level JDBC / pool failures into friendly, non-sensitive messages. */
public final class DbErrorTranslator {

    private DbErrorTranslator() {
    }

    public static DashboardException translate(Throwable t, String dbName, String tablePrefix) {
        if (t instanceof DashboardException de) return de;
        SQLException sql = null;
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            if (c instanceof SQLException se) {
                sql = se;
                break;
            }
        }
        String who = "database '" + dbName + "'";
        if (sql != null) {
            int code = sql.getErrorCode();
            String mysqlMessage = switch (code) {
                case 1044 -> "The MySQL account cannot access " + who + ". Check database privileges.";
                case 1045 -> "Login to " + who + " was rejected (invalid username/password or host grant).";
                case 1049 -> "The configured MySQL database for " + who + " does not exist.";
                case 1054 -> "A column was not found. The metadata schema may not be Spring Batch 5.x.";
                case 1146 -> "A Spring Batch metadata table was not found or is not readable in " + who
                        + ". Check dashboard.table-prefix ('" + tablePrefix + "'), the optional schema, and SELECT grants.";
                case 1205, 1317 -> "The query timed out or was cancelled. Narrow the filters or time range.";
                case 2002, 2003, 2005, 2013 ->
                        "Cannot reach " + who + " (host, port, or network problem, MySQL error " + code + ").";
                default -> null;
            };
            if (mysqlMessage != null) {
                return new DashboardException(DashboardException.Kind.DATABASE, mysqlMessage, t);
            }
            String ora = "ORA-" + String.format("%05d", code);
            String msg = switch (code) {
                case 1017 -> "Login to " + who + " was rejected (invalid username/password).";
                case 28000, 28001 -> "The account used for " + who + " is locked or its password has expired.";
                case 12541, 12514, 12505, 12170, 12154, 12545, 17002, 17008 ->
                        "Cannot reach " + who + " (listener/network/service-name problem, " + ora + ").";
                case 942 -> "A Spring Batch metadata table was not found or is not readable in " + who
                        + " (ORA-00942). Check dashboard.table-prefix ('" + tablePrefix + "'), the optional schema, and SELECT grants.";
                case 1031 -> "Insufficient privileges on " + who + " (ORA-01031).";
                case 904 -> "A column was not found (ORA-00904). The metadata schema may not be Spring Batch 5.x.";
                case 1013 -> "The query timed out and was cancelled (ORA-01013). Narrow the filters or time range.";
                default -> code != 0
                        ? "The database reported an error (" + ora + ")."
                        : "A database error occurred while talking to " + who + ".";
            };
            return new DashboardException(DashboardException.Kind.DATABASE, msg, t);
        }
        String text = String.valueOf(t.getMessage()).toLowerCase();
        if (text.contains("timeout") || text.contains("timed out") || text.contains("pool")) {
            return new DashboardException(DashboardException.Kind.DATABASE,
                    "Timed out while connecting to " + who + ". It may be unavailable or unreachable from this host.", t);
        }
        return new DashboardException(DashboardException.Kind.DATABASE,
                "Unable to query " + who + ". See application log for details.", t);
    }
}
