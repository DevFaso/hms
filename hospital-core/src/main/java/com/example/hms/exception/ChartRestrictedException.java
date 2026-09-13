package com.example.hms.exception;

import java.util.UUID;

/**
 * The chart exists and the caller is entitled to reach it, but the hospital
 * administrator has restricted it (E8 #54) and the caller holds no live
 * break-the-glass session for it. Deliberately NOT a 404: a restricted chart
 * is meant to be flagged loudly, and the portal answers it with the
 * declaration prompt rather than a not-found page. Mapped to 403 with the
 * code {@code CHART_RESTRICTED} by {@code GlobalExceptionHandler}.
 */
public class ChartRestrictedException extends RuntimeException {

    public static final String CODE = "CHART_RESTRICTED";

    private final transient UUID patientId;

    public ChartRestrictedException(UUID patientId) {
        super("This chart is restricted: declare a break-the-glass session with a stated reason to open it.");
        this.patientId = patientId;
    }

    public UUID getPatientId() {
        return patientId;
    }
}
