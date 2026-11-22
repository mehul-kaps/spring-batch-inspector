package com.ithaka.batchinspector.model;

import java.time.Duration;
import java.util.List;

public enum TimeRange {
    M15("15m", "15 min", Duration.ofMinutes(15)),
    H1("1h", "1 hour", Duration.ofHours(1)),
    H6("6h", "6 hours", Duration.ofHours(6)),
    H24("24h", "24 hours", Duration.ofHours(24)),
    D7("7d", "7 days", Duration.ofDays(7)),
    D30("30d", "30 days", Duration.ofDays(30)),
    ALL("all", "All time", null),
    CUSTOM("custom", "Custom", null);

    public static final TimeRange DEFAULT = H24;
    public static final List<TimeRange> ALL_VALUES = List.of(values());

    private final String code;
    private final String label;
    private final Duration duration;

    TimeRange(String code, String label, Duration duration) {
        this.code = code;
        this.label = label;
        this.duration = duration;
    }

    public String code() { return code; }
    public String label() { return label; }
    public Duration duration() { return duration; }

    public static TimeRange fromCode(String code) {
        if (code != null) {
            for (TimeRange r : values()) {
                if (r.code.equalsIgnoreCase(code.trim())) return r;
            }
        }
        return DEFAULT;
    }
}
