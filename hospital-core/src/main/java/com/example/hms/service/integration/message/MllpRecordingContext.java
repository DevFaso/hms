package com.example.hms.service.integration.message;

import com.example.hms.model.Hospital;

import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * The one place the MLLP paths derive what an
 * {@code integration_message_event} row is filed under.
 *
 * <p>There were three copies of each of these before, one per class that
 * records a rejection, and a copy that drifts is a silent failure: rows for
 * one sender split across two {@code integration_id} values and the operator
 * reading the DLQ sees half the story, or an over-long id fails the insert and
 * the row disappears entirely. On the ADT and A40 paths that row is the only
 * surviving record of why a message was refused, so a silent drop lands
 * exactly on the misconfigured sender it exists to diagnose.
 */
public final class MllpRecordingContext {

    /** The width of {@code integration_message_event.integration_id}. */
    private static final int INTEGRATION_ID_MAX = 120;

    private MllpRecordingContext() {}

    /**
     * {@code MLLP:<MSH-3>/<MSH-4>}, truncated to fit the column.
     *
     * <p>The truncation is not cosmetic: the column is
     * {@code VARCHAR(120) NOT NULL} and HL7 v2.5 permits 180 characters in
     * each of MSH-3 and MSH-4, so a sender with a pathological header would
     * otherwise fail the insert — which {@link IntegrationMessageRecorder}
     * swallows by design.
     */
    public static String integrationId(String sendingApplication, String sendingFacility) {
        String raw = "MLLP:" + placeholderIfBlank(sendingApplication)
            + "/" + placeholderIfBlank(sendingFacility);
        return raw.length() > INTEGRATION_ID_MAX ? raw.substring(0, INTEGRATION_ID_MAX) : raw;
    }

    /**
     * The receiving hospital's organization, or null when it cannot be read.
     *
     * <p>Call this only where a null answer is acceptable — from inside the
     * recorder's own try-catch, never on a path a legitimate message takes.
     * {@code Hospital.organization} is LAZY and {@code Organization} takes its
     * id from a field-access {@code @Id}, so reading it initialises the proxy.
     * The hospital arrives from
     * {@code MllpAllowedSenderService.resolveHospital}, whose read-only
     * transaction has already closed, and an inbound service's own transaction
     * is a different session that does not re-attach it —
     * {@code MllpAllowedSenderService} documents the same trap for
     * {@code Hospital.getId()}. Evaluated on the accepted path, a
     * {@code LazyInitializationException} here would abort a message that was
     * perfectly legitimate. Here the worst case is a DLQ row with a null
     * organization, which the column already allows.
     */
    public static UUID organizationId(Hospital hospital) {
        if (hospital == null) {
            return null;
        }
        try {
            return hospital.getOrganization() == null ? null : hospital.getOrganization().getId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String placeholderIfBlank(String value) {
        return StringUtils.hasText(value) ? value.trim() : "?";
    }
}
