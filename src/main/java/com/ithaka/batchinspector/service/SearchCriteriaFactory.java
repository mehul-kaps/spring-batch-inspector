package com.ithaka.batchinspector.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ithaka.batchinspector.config.DashboardProperties;
import com.ithaka.batchinspector.exception.DashboardException;
import com.ithaka.batchinspector.exception.DashboardException.Kind;
import com.ithaka.batchinspector.model.BatchStatus;
import com.ithaka.batchinspector.model.FilterForm;
import com.ithaka.batchinspector.model.SearchCriteria;
import com.ithaka.batchinspector.model.TimeRange;

/** Turns raw request strings into a validated, bounded SearchCriteria. Rejects anything that could be unsafe or very expensive. */
@Component
public class SearchCriteriaFactory {

    private static final int MAX_JOB_NAME_CHARS = 100;

    private final DashboardProperties props;

    public SearchCriteriaFactory(DashboardProperties props) {
        this.props = props;
    }

    public record Parsed(SearchCriteria criteria, List<String> notes) {
    }

    public Parsed parse(FilterForm f) {
        List<String> notes = new ArrayList<>();
        TimeRange range = TimeRange.fromCode(f.range());
        LocalDateTime from = null;
        LocalDateTime to = null;
        LocalDateTime now = LocalDateTime.now(props.zone());

        if (range.duration() != null) {
            from = now.minus(range.duration());
        } else if (range == TimeRange.CUSTOM) {
            from = parseDateTime(f.from(), "Start");
            to = parseDateTime(f.to(), "End");
            if (from != null && to != null && from.isAfter(to)) {
                throw new DashboardException(Kind.BAD_REQUEST, "The custom start time is after the end time.");
            }
            if (from == null && to == null) notes.add("Custom range left empty - searching all time.");
        }

        Long executionId = parsePositiveLong(f.executionId(), "Execution ID");
        Long instanceId = parsePositiveLong(f.instanceId(), "Job Instance ID");
        if (executionId != null && range != TimeRange.ALL) {
            notes.add("Execution ID is set, so the time range is ignored for this search.");
        }

        String name = f.jobName() == null ? "" : f.jobName().trim();
        if (name.length() > MAX_JOB_NAME_CHARS) {
            name = name.substring(0, MAX_JOB_NAME_CHARS);
            notes.add("Job name filter was shortened to " + MAX_JOB_NAME_CHARS + " characters.");
        }

        int limit = props.defaultMaxResults();
        if (f.max() != null && !f.max().isBlank()) {
            try {
                limit = Integer.parseInt(f.max().trim());
            } catch (NumberFormatException e) {
                throw new DashboardException(Kind.BAD_REQUEST, "Maximum Results must be a whole number.");
            }
        }
        if (limit < 1) {
            limit = 1;
            notes.add("Maximum Results was raised to 1.");
        }
        if (limit > props.maxResultsLimit()) {
            limit = props.maxResultsLimit();
            notes.add("Maximum Results is capped at " + props.maxResultsLimit() + " to keep queries and the page light.");
        }

        BatchStatus status = null;
        String st = f.status() == null ? "" : f.status().trim();
        if (!st.isEmpty() && !"all".equalsIgnoreCase(st)) {
            status = BatchStatus.of(st);
        }

        return new Parsed(new SearchCriteria(f.db(), from, to, executionId, instanceId, name.isEmpty() ? null : name, limit, status), notes);
    }

    private static Long parsePositiveLong(String s, String label) {
        if (s == null || s.isBlank()) return null;
        try {
            long v = Long.parseLong(s.trim());
            if (v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new DashboardException(Kind.BAD_REQUEST, label + " must be a positive whole number.");
        }
    }

    private static LocalDateTime parseDateTime(String s, String label) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (Exception e) {
            throw new DashboardException(Kind.BAD_REQUEST, label + " date/time is not valid.");
        }
    }
}
