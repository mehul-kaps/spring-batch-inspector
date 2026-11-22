package com.ithaka.batchinspector.util;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.ithaka.batchinspector.config.DashboardProperties;
import com.ithaka.batchinspector.model.BatchStatus;

/** Presentation helpers exposed to Thymeleaf as {@code @fmt}. Stateless and null-safe. */
@Component("fmt")
public class Formatters {

    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM dd  HH:mm:ss");
    private static final DateTimeFormatter LOCAL_INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final String DASH = "—";

    private final ZoneId zone;

    public Formatters(DashboardProperties props) {
        this.zone = props.zone();
    }

    public ZoneId zone() {
        return zone;
    }

    public String zoneId() {
        return zone.getId();
    }

    public String zoneLabel() {
        ZoneOffset off = zone.getRules().getOffset(Instant.now());
        return zone.getId() + " (UTC" + (off.getTotalSeconds() == 0 ? "" : off.getId()) + ")";
    }

    public long nowEpoch() {
        return System.currentTimeMillis();
    }

    public LocalDateTime nowLocal() {
        return LocalDateTime.now(zone);
    }

    public String inputValue(LocalDateTime t) {
        return t == null ? "" : LOCAL_INPUT.format(t);
    }

    public Long epoch(LocalDateTime t) {
        return t == null ? null : t.atZone(zone).toInstant().toEpochMilli();
    }

    public String ts(LocalDateTime t) {
        return t == null ? DASH : FULL.format(t);
    }

    public String tsShort(LocalDateTime t) {
        return t == null ? DASH : SHORT.format(t);
    }

    public String duration(Long ms) {
        if (ms == null) return DASH;
        long v = Math.max(0, ms);
        if (v < 1000) return v + " ms";
        if (v < 60_000) return String.format(Locale.US, "%.1f s", v / 1000.0);
        long s = v / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return String.format(Locale.US, "%dd %02dh %02dm", d, h, m);
        if (h > 0) return String.format(Locale.US, "%dh %02dm %02ds", h, m, sec);
        return String.format(Locale.US, "%dm %02ds", m, sec);
    }

    /** Elapsed ms: end-start, or now-start while the job/step is in progress; null when it cannot be known. */
    public Long elapsedMs(LocalDateTime start, LocalDateTime end, String status) {
        if (start == null) return null;
        long s = epoch(start);
        if (end != null) return Math.max(0, epoch(end) - s);
        if (BatchStatus.of(status).inProgress()) return Math.max(0, System.currentTimeMillis() - s);
        return null;
    }

    /** True when the item has no end time but is persisted as STARTING/STARTED/STOPPING. */
    public boolean live(LocalDateTime start, LocalDateTime end, String status) {
        return start != null && end == null && BatchStatus.of(status).inProgress();
    }

    public String num(Long v) {
        return v == null ? DASH : NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    public String rate(Long count, Long ms) {
        if (count == null || ms == null || ms <= 0) return DASH;
        double perSec = count * 1000.0 / ms;
        return perSec >= 100 ? String.format(Locale.US, "%,.0f/s", perSec) : String.format(Locale.US, "%.1f/s", perSec);
    }

    /** One-line, length-limited preview of a possibly multi-line exit message. */
    public String oneLine(String s, int max) {
        if (s == null || s.isBlank()) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    public String statusCss(String status) {
        return BatchStatus.of(status).css();
    }

    public String statusLabel(String status) {
        BatchStatus b = BatchStatus.of(status);
        // Keep the real stored text for values we do not recognise so nothing is hidden from the developer.
        if (b == BatchStatus.UNKNOWN && status != null && !"UNKNOWN".equalsIgnoreCase(status.trim())) return status;
        if (status == null) return "NULL";
        return b.label();
    }

    public String statusGlyph(String status) {
        return BatchStatus.of(status).glyph();
    }

    /** Deterministic hue (0-359) so every job name always gets the same colour. */
    public int hue(String name) {
        int h = name == null ? 0 : name.hashCode();
        h ^= (h >>> 16);
        h *= 0x45d9f3b;
        h ^= (h >>> 16);
        return Math.floorMod(h, 360);
    }

    public String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.split("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0)));
            if (sb.length() == 2) break;
        }
        if (sb.length() == 0) return name.substring(0, 1).toUpperCase();
        return sb.toString();
    }

    /** Percentage 0-100 (null-safe) used for the tiny relative bars. */
    public int pct(Long part, Long total) {
        if (part == null || total == null || total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, Math.round(part * 100.0 / total)));
    }

    public boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
