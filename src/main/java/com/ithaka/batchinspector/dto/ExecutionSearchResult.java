package com.ithaka.batchinspector.dto;

import java.util.List;
import java.util.Map;

import com.ithaka.batchinspector.model.BatchStatus;

/**
 * @param counts   counts of ALL executions matching the sidebar filters (before the display limit), per status
 * @param total    sum of {@code counts}
 * @param matching executions matching the filters AND the selected status (before the display limit)
 */
public record ExecutionSearchResult(
        Map<BatchStatus, Long> counts,
        long total,
        long matching,
        List<ExecutionSummary> rows,
        int limit,
        long maxDurationMs) {

    public boolean truncated() {
        return matching > rows.size();
    }
}
