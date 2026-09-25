package com.example.hms.service.integration.message;

import com.example.hms.model.Hospital;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * The one place the MLLP paths derive what an
 * {@code integration_message_event} row is filed under.
 *
 * <p>A copy that drifts is a silent failure: rows for one sender split across
 * two {@code integration_id} values and the operator reading the DLQ sees half
 * the story, or an over-long id fails the insert and the row disappears
 * entirely. On the ADT and A40 paths that row is the only surviving record of
 * why a message was refused, so a silent drop lands exactly on the
 * misconfigured sender it exists to diagnose.
 *
 * <p>The dispatcher and the ADT and A40 services use this.
 * {@code MllpInboundLabServiceImpl} still has its own pair, and its
 * {@code buildIntegrationId} is the copy with no truncation — migrating it is
 * a two-line change this PR leaves to the lab stream, which has that file
 * open.
 */
public final class MllpRecordingContext {

    private static final Logger log = LoggerFactory.getLogger(MllpRecordingContext.class);

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
     * <p>{@code Hospital.organization} is LAZY, {@code Organization} takes its
     * id from a field-access {@code @Id}, and the hospital reaches an MLLP
     * worker thread detached from a read-only transaction that has already
     * closed — so reading this would throw, were it not for
     * {@code MllpAllowedSenderServiceImpl.resolveHospital} initialising the
     * organization before handing the hospital out. That is where the problem
     * is solved; this method just reads the result.
     *
     * <p>The catch is therefore not the normal path and must not become one.
     * It exists because the alternative is worse: on the ADT and A40 paths
     * this is called while building the row that is the only record of why a
     * message was refused, and losing that row to an initialisation bug
     * upstream would take the reason with it. A null organization is a
     * degraded row; no row is no evidence. The WARN is how you find out the
     * initialisation has regressed.
     */
    public static UUID organizationId(Hospital hospital) {
        if (hospital == null) {
            return null;
        }
        try {
            return hospital.getOrganization() == null ? null : hospital.getOrganization().getId();
        } catch (RuntimeException ex) {
            // Never silently: a null organization on a DLQ row otherwise reads
            // the same whether the hospital has none or the proxy could not be
            // read, and the row is the only record of the rejection. The
            // hospital is NOT dereferenced for the message — Hospital.getId()
            // off a detached association is the same trap, and throwing out of
            // this catch would defeat the point of having it.
            log.warn("MLLP recorder could not read the receiving hospital's organization; "
                + "the integration message row will be filed without one", ex);
            return null;
        }
    }

    /**
     * A correlation id that is the same for every occurrence of the same
     * problem, and different for different problems.
     *
     * <p>{@code countUnresolvedDeadLetters} counts a {@code FAILED} row only
     * when no later row shares its {@code correlationId}, so this is what
     * decides whether a retried refusal supersedes its own previous row or
     * stacks a new dead letter on the operator's badge. The ACK for a refused
     * MLLP message is AE, which senders treat as transient and retry on a
     * timer, so without this one misconfigured feed would post thousands of
     * unresolved dead letters a day and bury the refusals nobody has seen.
     *
     * <p>Derived from the sender, the message type and the reason, and from
     * <b>nothing per-message</b>: no MSH-10, no identifier, no timestamp.
     * Anything per-message here would defeat the whole point, and an
     * identifier here would put PHI in an indexed column. Reasons are the
     * fixed strings the call sites pass, never text built from a message.
     *
     * <p>A name-based UUID rather than a readable key: it is deterministic,
     * always fits the {@code VARCHAR(120)} column, and is the same shape as
     * the random ids every other row carries, so nothing downstream has to
     * learn a second format.
     */
    public static String rejectionCorrelationId(String integrationId, String messageType,
                                                String reason) {
        String key = "mllp-reject|" + integrationId + "|" + messageType + "|" + reason;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String placeholderIfBlank(String value) {
        return StringUtils.hasText(value) ? value.trim() : "?";
    }
}
