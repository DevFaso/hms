package com.example.hms.enums;

import java.util.Arrays;

/**
 * The events the server itself writes into an imaging report's status
 * history (r44, D1574 class). Their {@link #storedText()} is what the
 * {@code status_reason} column has always held, canonical English, so rows
 * written before this enum read back as the same event; the response carries
 * the {@link ImagingReportHistoryEvent} code and the portal translates it.
 * A reason a person typed (a void) matches none of them and is shown as typed.
 */
public enum ImagingReportHistoryEvent {
    AUTHORED("Report authored"),
    SIGNED("Report signed"),
    CRITICAL_ACKNOWLEDGED("Critical finding acknowledged");

    private final String storedText;

    ImagingReportHistoryEvent(String storedText) {
        this.storedText = storedText;
    }

    /** The text written to {@code status_reason}; never shown to a reader. */
    public String storedText() {
        return storedText;
    }

    /** The event a stored reason records, or null for a reason a person typed. */
    public static ImagingReportHistoryEvent fromStoredText(String statusReason) {
        if (statusReason == null) {
            return null;
        }
        return Arrays.stream(values())
            .filter(event -> event.storedText.equals(statusReason.trim()))
            .findFirst()
            .orElse(null);
    }
}
