package com.ithaka.batchinspector.dto;

import java.util.List;

public record HealthResult(boolean ok, String message, long latencyMs, List<String> missingTables) {
}
