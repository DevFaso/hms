package com.example.hms.service.timeline;

import com.example.hms.utility.MessageUtil;

/**
 * QA D1027: the chronology listed a cancelled morphine order and three
 * cancelled drafts exactly like the signed orders; the status was only in the
 * row's metadata, which the chart does not print. Every chronology row whose
 * record has a status names it in its summary, in the reader's language,
 * through this one helper. {@code TimelineStatusGuardTest} fails when a row
 * puts a status in its metadata without calling it.
 */
public final class TimelineStatus {

    private TimelineStatus() {
    }

    /**
     * The status in the reader's language: the type's own label
     * ({@code timeline.status.PrescriptionStatus.CANCELLED} "Annulée"), so the
     * grammatical gender follows the record, else the chronology's generic one.
     */
    public static String label(Enum<?> status) {
        String own = MessageUtil.resolveOrNull(null,
            "timeline.status." + status.getDeclaringClass().getSimpleName() + "." + status.name());
        return own != null ? own : VisitTimelineRows.label(status);
    }

    /**
     * QA D959 (round 18): {@code summary (label)} for a record whose status is
     * free text, not an enum (an immunization's COMPLETED / REFUSED…); the
     * caller resolves the label. The summary alone when there is no label.
     */
    public static String withStatusLabel(String summary, String statusLabel) {
        if (statusLabel == null || statusLabel.isBlank()) {
            return summary;
        }
        return MessageUtil.resolveOrRaw("timeline.withStatus", summary, statusLabel);
    }

    /** {@code summary (status)}; the summary alone when there is no status. */
    public static String withStatus(String summary, Enum<?> status) {
        if (status == null) {
            return summary;
        }
        return MessageUtil.resolveOrRaw("timeline.withStatus", summary, label(status));
    }
}
