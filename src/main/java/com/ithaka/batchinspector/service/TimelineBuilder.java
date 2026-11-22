package com.ithaka.batchinspector.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ithaka.batchinspector.dto.ExecutionSummary;
import com.ithaka.batchinspector.dto.StepDto;
import com.ithaka.batchinspector.dto.TimelineDto;
import com.ithaka.batchinspector.dto.TimelineDto.Bar;
import com.ithaka.batchinspector.dto.TimelineDto.Gap;
import com.ithaka.batchinspector.model.BatchStatus;
import com.ithaka.batchinspector.util.Formatters;

/**
 * Builds the Gantt model strictly from persisted timestamps. It never assumes steps ran one after another:
 * every step keeps its own start/end, overlaps are flagged, and idle gaps are only reported where the stored
 * timestamps prove nothing was running.
 */
@Component
public class TimelineBuilder {

    private final Formatters fmt;

    public TimelineBuilder(Formatters fmt) {
        this.fmt = fmt;
    }

    public TimelineDto build(ExecutionSummary job, List<StepDto> steps) {
        long now = System.currentTimeMillis();
        BatchStatus jobStatus = BatchStatus.of(job.status());
        boolean jobRunning = jobStatus.inProgress() && job.endTime() == null;

        Long jobStart = fmt.epoch(job.startTime());
        Long jobEnd = fmt.epoch(job.endTime());

        // 1. Window
        long minStart = Long.MAX_VALUE;
        long maxEnd = Long.MIN_VALUE;
        if (jobStart != null) minStart = jobStart;
        for (StepDto s : steps) {
            Long st = fmt.epoch(s.startTime());
            Long en = fmt.epoch(s.endTime());
            if (st != null) minStart = Math.min(minStart, st);
            if (en != null) maxEnd = Math.max(maxEnd, en);
            else if (st != null) maxEnd = Math.max(maxEnd, st);
        }
        if (minStart == Long.MAX_VALUE) {
            Long created = fmt.epoch(job.createTime());
            minStart = created != null ? created : now;
        }
        long windowEnd;
        if (jobRunning) windowEnd = Math.max(now, Math.max(maxEnd, minStart));
        else if (jobEnd != null) windowEnd = Math.max(jobEnd, maxEnd);
        else windowEnd = Math.max(maxEnd, minStart);
        if (windowEnd <= minStart) windowEnd = minStart + 1000;
        final long t0 = minStart;
        final long t1 = windowEnd;
        final long span = t1 - t0;

        // 2. Bars
        List<Bar> raw = new ArrayList<>();
        List<String> untimed = new ArrayList<>();
        for (StepDto s : steps) {
            Long st = fmt.epoch(s.startTime());
            if (st == null) {
                untimed.add(s.name() + " [" + fmt.statusLabel(s.status()) + "]");
                continue;
            }
            Long en = fmt.epoch(s.endTime());
            BatchStatus ss = BatchStatus.of(s.status());
            boolean inProgress = false;
            boolean endUnknown = false;
            long end;
            if (en != null) {
                end = Math.max(en, st);
            } else if (ss.inProgress() || jobRunning) {
                inProgress = true;
                end = Math.max(now, st);
            } else {
                endUnknown = true; // finished job but the step never recorded an end (killed JVM, crash...)
                end = (jobEnd != null && jobEnd >= st) ? jobEnd : st;
            }
            boolean zero = en != null && end == st;
            long dur = end - st;
            raw.add(new Bar(
                    s.id(), s.name(), s.status(), fmt.statusLabel(s.status()), ss.css(),
                    st, end, inProgress, endUnknown, zero, endUnknown ? null : dur,
                    Math.min(100.0, dur * 100.0 / span), 0, false,
                    fmt.ts(s.startTime()), s.endTime() == null ? (inProgress ? "running…" : "not recorded") : fmt.ts(s.endTime()),
                    counters(s), s.exitCode(), cut(s.exitMessage(), 1500)));
        }

        // 3. Slowest ranking + overlap flags
        List<Bar> byDur = raw.stream().filter(b -> b.durationMs() != null && b.durationMs() > 0)
                .sorted(Comparator.comparingLong((Bar b) -> b.durationMs()).reversed()).toList();
        List<Bar> out = new ArrayList<>();
        for (Bar b : raw) {
            int rank = 0;
            if (raw.size() > 1) {
                int idx = byDur.indexOf(b);
                if (idx >= 0 && idx < 3) rank = idx + 1;
            }
            boolean parallel = false;
            for (Bar o : raw) {
                if (o != b && b.startMs() < o.endMs() && o.startMs() < b.endMs()) {
                    parallel = true;
                    break;
                }
            }
            out.add(new Bar(b.stepId(), b.name(), b.status(), b.statusLabel(), b.statusCss(), b.startMs(), b.endMs(),
                    b.inProgress(), b.endUnknown(), b.zeroDuration(), b.durationMs(), b.pctOfJob(), rank, parallel,
                    b.startText(), b.endText(), b.counters(), b.exitCode(), b.exitMessage()));
        }

        // 4. Idle gaps (nothing running according to persisted timestamps)
        List<Gap> gaps = new ArrayList<>();
        List<Bar> sorted = out.stream().sorted(Comparator.comparingLong(Bar::startMs)).toList();
        if (!sorted.isEmpty()) {
            long cursor = Math.min(t0, sorted.get(0).startMs());
            if (jobStart != null && sorted.get(0).startMs() > jobStart) {
                gaps.add(new Gap(jobStart, sorted.get(0).startMs()));
            }
            cursor = sorted.get(0).endMs();
            for (int i = 1; i < sorted.size(); i++) {
                Bar b = sorted.get(i);
                if (b.startMs() > cursor) gaps.add(new Gap(cursor, b.startMs()));
                cursor = Math.max(cursor, b.endMs());
            }
            if (!jobRunning && jobEnd != null && jobEnd > cursor) gaps.add(new Gap(cursor, jobEnd));
        }

        return new TimelineDto(t0, t1, now, jobRunning, job.status(), fmt.zone().getId(), fmt.zoneLabel(), out, gaps, untimed);
    }

    private String counters(StepDto s) {
        return "read " + fmt.num(s.readCount()) + " · write " + fmt.num(s.writeCount()) + " · filter " + fmt.num(s.filterCount())
                + " · skips r/w/p " + fmt.num(s.readSkipCount()) + "/" + fmt.num(s.writeSkipCount()) + "/" + fmt.num(s.processSkipCount())
                + " · commits " + fmt.num(s.commitCount()) + " · rollbacks " + fmt.num(s.rollbackCount());
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n… (truncated, full text in the Steps tab)";
    }
}
