package com.example.hms.service.integration.message;

import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an {@code integration_message_event} row gets filed under.
 *
 * <p>Both of these are small, and both have already been got wrong once: the
 * truncation was missing from two of the three copies this class replaced, and
 * an over-long {@code integration_id} does not fail loudly — the insert throws
 * inside {@link IntegrationMessageRecorder}, which swallows it by design, and
 * the row simply never appears. On the ADT and A40 paths that row is the only
 * record of why a message was refused.
 */
class MllpRecordingContextTest {

    @Test
    @DisplayName("The id is MLLP:<MSH-3>/<MSH-4>, trimmed, with a placeholder for a blank side")
    void theIdIsTheSenderPair() {
        assertThat(MllpRecordingContext.integrationId("MINDRAY", "LAB-A"))
            .isEqualTo("MLLP:MINDRAY/LAB-A");
        assertThat(MllpRecordingContext.integrationId("  MINDRAY  ", "  LAB-A  "))
            .isEqualTo("MLLP:MINDRAY/LAB-A");
        assertThat(MllpRecordingContext.integrationId(null, "  "))
            .isEqualTo("MLLP:?/?");
    }

    @Test
    @DisplayName("The sender pair is upper-cased, because that is how the allowlist matches it")
    void theSenderPairIsNormalisedLikeTheAllowlistMatchesIt() {
        // MllpAllowedSenderServiceImpl.lookup matches on
        // trim().toUpperCase(ROOT), so all three of these resolve to the same
        // allowlisted sender. If they produced three integration ids they
        // would produce three "stable" correlation ids too, and one sender
        // could defeat the dedupe - and split its own DLQ rows - purely by
        // varying the case of its own headers.
        assertThat(MllpRecordingContext.integrationId("mindray", "lab-a"))
            .isEqualTo("MLLP:MINDRAY/LAB-A")
            .isEqualTo(MllpRecordingContext.integrationId("MinDray", " LAB-a "))
            .isEqualTo(MllpRecordingContext.integrationId("MINDRAY", "LAB-A"));

        assertThat(MllpRecordingContext.rejectionCorrelationId(
                MllpRecordingContext.integrationId("mindray", "lab-a"), "T", "r"))
            .isEqualTo(MllpRecordingContext.rejectionCorrelationId(
                MllpRecordingContext.integrationId("MINDRAY", "LAB-A"), "T", "r"));
    }

    @Test
    @DisplayName("An HL7-legal but over-long sender pair is truncated to the column width")
    void anOverLongSenderPairIsTruncated() {
        // HL7 v2.5 permits 180 characters in each of MSH-3 and MSH-4;
        // integration_message_event.integration_id is VARCHAR(120) NOT NULL.
        // Without the cap the insert fails, the recorder swallows it, and the
        // DLQ entry for the very sender whose configuration is wrong is the
        // one that goes missing.
        String longApp = "A".repeat(180);
        String longFacility = "F".repeat(180);

        String id = MllpRecordingContext.integrationId(longApp, longFacility);

        assertThat(id).hasSize(120).startsWith("MLLP:AAA");
    }

    @Test
    @DisplayName("The sender label is capped, so a log line cannot be padded by its sender")
    void theSenderLabelIsCapped() {
        // MSH-3 and MSH-4 are read verbatim by Hl7MessageInspector with no
        // length check, and every MLLP refusal logs them - including the
        // not-allowlisted branch, which anyone reaching the port hits. Without
        // a cap here, collapsing capped(normalised(x)) back to normalised(x)
        // would reopen that with a green suite, which is why this exists.
        String longApp = "A".repeat(400);
        String longFacility = "F".repeat(400);

        String label = MllpRecordingContext.senderLabel(longApp, longFacility);

        // 180 each - what HL7 v2.5 allows in those fields - plus the slash.
        assertThat(label).hasSize(180 + 1 + 180);
        assertThat(label).isEqualTo("A".repeat(180) + "/" + "F".repeat(180));
        // Normalised the same way as the id, so one sender stays one sender.
        assertThat(MllpRecordingContext.senderLabel(" mindray ", "lab-a"))
            .isEqualTo("MINDRAY/LAB-A");
        assertThat(MllpRecordingContext.senderLabel(null, "  ")).isEqualTo("?/?");
    }

    @Test
    @DisplayName("The organization is the hospital's, and null when it has none")
    void theOrganizationIsTheHospitals() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setOrganization(organization);

        assertThat(MllpRecordingContext.organizationId(hospital)).isEqualTo(organizationId);

        hospital.setOrganization(null);
        assertThat(MllpRecordingContext.organizationId(hospital)).isNull();
        assertThat(MllpRecordingContext.organizationId(null)).isNull();
    }

    @Test
    @DisplayName("The rejection correlation id is stable per problem and carries nothing per-message")
    void theRejectionCorrelationIdIsStablePerProblem() {
        String first = MllpRecordingContext.rejectionCorrelationId(
            "MLLP:REG/HOSP-B", "ADT^A08", "cross-tenant rejection");
        String again = MllpRecordingContext.rejectionCorrelationId(
            "MLLP:REG/HOSP-B", "ADT^A08", "cross-tenant rejection");

        // Same problem, same id: countUnresolvedDeadLetters then treats each
        // retry as superseding the last rather than as a new dead letter.
        assertThat(first).isEqualTo(again);
        // It is a UUID, so it fits correlation_id VARCHAR(120) and looks like
        // every other row's id.
        assertThat(UUID.fromString(first)).hasToString(first);

        // Different problems stay apart, or one refusal hides another.
        assertThat(first).isNotEqualTo(MllpRecordingContext.rejectionCorrelationId(
            "MLLP:REG/HOSP-B", "ADT^A08", "PID-3 not found"));
        assertThat(first).isNotEqualTo(MllpRecordingContext.rejectionCorrelationId(
            "MLLP:REG/HOSP-B", "ADT^A40", "cross-tenant rejection"));
        assertThat(first).isNotEqualTo(MllpRecordingContext.rejectionCorrelationId(
            "MLLP:OTHER/HOSP-B", "ADT^A08", "cross-tenant rejection"));
    }

    @Test
    @DisplayName("No scope means no correlation id, not a global one")
    void aMissingScopeMeansNoDedupeAtAll() {
        // The trap this closes: concatenating a null scope yields the literal
        // "null" and therefore a perfectly stable, perfectly GLOBAL id - a
        // shared scope arrived at by accident. Shared is the one thing a
        // correlation scope must never be: the newest row wins the dead-letter
        // count and the first row of a window owns the stored body, so one
        // shared id lets any sender silence another's entry and suppress its
        // evidence.
        assertThat(MllpRecordingContext.rejectionCorrelationId(null, "T", "r")).isNull();
        assertThat(MllpRecordingContext.rejectionCorrelationId("  ", "T", "r")).isNull();
    }

    @Test
    @DisplayName("A hospital that cannot be read degrades the row instead of losing it")
    void anUnreadableOrganizationDegradesTheRowRatherThanLosingIt() {
        // MllpAllowedSenderServiceImpl.resolveHospital initialises the
        // organization inside its transaction, so this should never happen.
        // If it regresses, the rejection row must still be written: a null
        // organization is a degraded row, no row is no evidence of why a
        // message was refused.
        Hospital unreadable = new Hospital() {
            @Override
            public Organization getOrganization() {
                throw new org.hibernate.LazyInitializationException("detached proxy");
            }
        };
        unreadable.setId(UUID.randomUUID());

        assertThat(MllpRecordingContext.organizationId(unreadable)).isNull();
    }
}
