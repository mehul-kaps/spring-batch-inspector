package com.ithaka.batchinspector.dto;

/** Display-only (never deserialised) view of a stored ExecutionContext. */
public record ContextBlock(String title, String scope, String text, boolean truncated, boolean masked, boolean empty) {
}
