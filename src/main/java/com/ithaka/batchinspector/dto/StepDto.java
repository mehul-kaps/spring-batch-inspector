package com.ithaka.batchinspector.dto;

import java.time.LocalDateTime;

public record StepDto(
        long id,
        String name,
        String status,
        Long version,
        LocalDateTime createTime,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime lastUpdated,
        Long readCount,
        Long writeCount,
        Long filterCount,
        Long readSkipCount,
        Long writeSkipCount,
        Long processSkipCount,
        Long commitCount,
        Long rollbackCount,
        String exitCode,
        String exitMessage) {
}
