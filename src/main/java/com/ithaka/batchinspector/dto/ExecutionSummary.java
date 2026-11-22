package com.ithaka.batchinspector.dto;

import java.time.LocalDateTime;

public record ExecutionSummary(
        long id,
        long instanceId,
        String jobName,
        String status,
        Long version,
        LocalDateTime createTime,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime lastUpdated,
        String exitCode,
        String exitMessage,
        int stepCount,
        int failedStepCount) {
}
