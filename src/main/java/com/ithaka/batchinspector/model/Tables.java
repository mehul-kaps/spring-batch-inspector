package com.ithaka.batchinspector.model;

import java.util.regex.Pattern;

/**
 * Fully-qualified Spring Batch 5.x metadata table names. Identifiers cannot be bind variables,
 * so they are strictly validated before being concatenated into SQL.
 */
public record Tables(String instance, String exec, String params, String step, String execCtx, String stepCtx) {

    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$]{0,60}$");

    public static Tables of(String schema, String prefix) {
        String p = requireSafe(prefix, "table prefix");
        String owner = (schema == null || schema.isBlank()) ? "" : requireSafe(schema.trim(), "schema") + ".";
        String base = owner + p;
        return new Tables(
                base + "JOB_INSTANCE",
                base + "JOB_EXECUTION",
                base + "JOB_EXECUTION_PARAMS",
                base + "STEP_EXECUTION",
                base + "JOB_EXECUTION_CONTEXT",
                base + "STEP_EXECUTION_CONTEXT");
    }

    private static String requireSafe(String s, String what) {
        if (s == null || !SAFE_IDENT.matcher(s.trim()).matches()) {
            throw new IllegalStateException("Invalid database identifier for " + what
                    + " (allowed: letters, digits, _ and $; must start with a letter)");
        }
        return s.trim();
    }
}
