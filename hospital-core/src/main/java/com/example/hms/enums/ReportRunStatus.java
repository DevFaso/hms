package com.example.hms.enums;

/**
 * Lifecycle of one scheduled-report run (P3 #25a). The row is inserted
 * as GENERATING BEFORE any work happens — the UNIQUE (definition,
 * period) claim — so two sweep instances can never email one period
 * twice.
 */
public enum ReportRunStatus {
    GENERATING,
    SUCCEEDED,
    FAILED
}
