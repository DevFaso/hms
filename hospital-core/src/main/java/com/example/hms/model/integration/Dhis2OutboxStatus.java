package com.example.hms.model.integration;

/** Per-value lifecycle inside a {@link Dhis2ExportRun}. */
public enum Dhis2OutboxStatus {

    PENDING,
    SENT,
    FAILED
}
