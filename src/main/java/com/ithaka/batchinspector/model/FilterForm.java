package com.ithaka.batchinspector.model;

/** Raw (string) filter values echoed back into the sidebar. Never null. */
public record FilterForm(
        String db,
        String range,
        String from,
        String to,
        String executionId,
        String instanceId,
        String jobName,
        String max,
        String status,
        String selected) {
}
