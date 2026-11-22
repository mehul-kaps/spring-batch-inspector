package com.ithaka.batchinspector.dto;

/** What the UI is allowed to know about a configured database (never credentials / URL). */
public record DatabaseOption(String id, String name, String environment) {
}
