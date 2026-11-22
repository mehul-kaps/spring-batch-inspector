package com.ithaka.batchinspector.dto;

import java.util.List;

/** Everything the browser needs to draw the step Gantt. All instants are epoch millis in the display zone. */
public record TimelineDto(
        long startMs,
        long endMs,
        long nowMs,
        boolean running,
        String jobStatus,
        String zoneId,
        String zoneLabel,
        List<Bar> bars,
        List<Gap> gaps,
        List<String> untimed) {

    public record Bar(
            long stepId,
            String name,
            String status,
            String statusLabel,
            String statusCss,
            Long startMs,
            Long endMs,
            boolean inProgress,
            boolean endUnknown,
            boolean zeroDuration,
            Long durationMs,
            double pctOfJob,
            int slowRank,
            boolean parallel,
            String startText,
            String endText,
            String counters,
            String exitCode,
            String exitMessage) {
    }

    /** Idle time between the latest end seen so far and the next step's start. */
    public record Gap(long fromMs, long toMs) {
    }
}
