package com.example.hms.model.integration;

/**
 * Lifecycle of a {@link Dhis2ExportRun}.
 *
 * <p>{@code PARTIAL} is reached when the DHIS2 import summary reports a
 * non-zero {@code ignored} count alongside an HTTP 200/201 — some values
 * persisted, some did not. The orchestrator records the per-value
 * outcome on the {@link Dhis2ExportOutbox} row.
 */
public enum Dhis2ExportStatus {

    PENDING,
    SUCCESS,
    PARTIAL,
    FAILED
}
