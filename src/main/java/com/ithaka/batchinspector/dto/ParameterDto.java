package com.ithaka.batchinspector.dto;

public record ParameterDto(String name, String type, String value, boolean identifying, boolean masked) {
}
