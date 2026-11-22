package com.ithaka.batchinspector.exception;

/** A failure that is safe to show to the user (no stack traces, no credentials, no SQL). */
public class DashboardException extends RuntimeException {

    public enum Kind { BAD_REQUEST, NOT_FOUND, DATABASE }

    private final Kind kind;

    public DashboardException(Kind kind, String userMessage) {
        super(userMessage);
        this.kind = kind;
    }

    public DashboardException(Kind kind, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
