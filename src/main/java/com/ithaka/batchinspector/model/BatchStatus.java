package com.ithaka.batchinspector.model;

import java.util.List;
import java.util.Locale;

/** Spring Batch's {@code BatchStatus} values (kept as a plain enum: this app never depends on spring-batch-core). */
public enum BatchStatus {
    COMPLETED("Completed", "completed", "✓"),
    FAILED("Failed", "failed", "✕"),
    STARTED("Started", "started", "▶"),
    STARTING("Starting", "starting", "◔"),
    STOPPING("Stopping", "stopping", "◼"),
    STOPPED("Stopped", "stopped", "■"),
    ABANDONED("Abandoned", "abandoned", "⊘"),
    UNKNOWN("Unknown", "unknown", "?");

    /** Card order in the status strip (after "All"). */
    public static final List<BatchStatus> CARD_ORDER = List.of(
            COMPLETED, FAILED, STARTED, STARTING, STOPPING, STOPPED, ABANDONED, UNKNOWN);

    private final String label;
    private final String css;
    private final String glyph;

    BatchStatus(String label, String css, String glyph) {
        this.label = label;
        this.css = css;
        this.glyph = glyph;
    }

    public String label() { return label; }
    public String css() { return css; }
    public String glyph() { return glyph; }

    public boolean inProgress() {
        return this == STARTED || this == STARTING || this == STOPPING;
    }

    /** Tolerant parse: null / future / misspelled values become UNKNOWN instead of failing. */
    public static BatchStatus of(String raw) {
        if (raw == null) return UNKNOWN;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** True when the raw string is one of the seven real Spring Batch statuses. */
    public static boolean isKnown(String raw) {
        if (raw == null) return false;
        BatchStatus s = of(raw);
        return s != UNKNOWN || "UNKNOWN".equalsIgnoreCase(raw.trim());
    }
}
