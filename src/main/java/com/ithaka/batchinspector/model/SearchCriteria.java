package com.ithaka.batchinspector.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Validated search input. {@code from}/{@code to} are in the batch JVM's local time
 * (how Spring Batch 5 persists TIMESTAMP columns). {@code status == null} means "All".
 */
public record SearchCriteria(
        String dbId,
        LocalDateTime from,
        LocalDateTime to,
        List<Long> executionIds,
        List<Long> instanceIds,
        String jobNameContains,
        int limit,
        BatchStatus status) {

    public SearchCriteria withStatus(BatchStatus s) {
        return new SearchCriteria(dbId, from, to, executionIds, instanceIds, jobNameContains, limit, s);
    }
}
