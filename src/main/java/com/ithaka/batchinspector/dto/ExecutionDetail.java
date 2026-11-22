package com.ithaka.batchinspector.dto;

import java.util.List;

public record ExecutionDetail(
        ExecutionSummary summary,
        String jobKey,
        List<ParameterDto> parameters,
        List<StepDto> steps,
        TimelineDto timeline,
        long slowestStepMs) {
}
