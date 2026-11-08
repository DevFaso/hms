package com.example.hms.fhir.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import org.hl7.fhir.r4.model.MedicationRequest.MedicationRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 review (round 40): every MedicationRequest was exported as "active", so a stopped order went
 * out of the FHIR exports as one the patient was still on.
 */
class MedicationRequestStatusMappingTest {

    private static Prescription signedThen(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setStatus(PrescriptionStatus.SIGNED);
        p.setStatus(status);
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    @ParameterizedTest
    @CsvSource({
        "SIGNED, ACTIVE", "TRANSMITTED, ACTIVE", "PARTIALLY_FILLED, ACTIVE", "SENT_TO_PARTNER, ACTIVE",
        "PARTNER_ACCEPTED, ACTIVE", "PRINTED_FOR_PATIENT, ACTIVE",
        "DISCONTINUED, STOPPED", "CANCELLED, CANCELLED",
        "DISPENSED, COMPLETED", "PARTNER_DISPENSED, COMPLETED",
        "PENDING_CLARIFICATION, ONHOLD", "PENDING_STOCK, ONHOLD", "TRANSMISSION_FAILED, ONHOLD", "PARTNER_REJECTED, ONHOLD"})
    void aSignedOrderCarriesItsOwnStatus(PrescriptionStatus status, MedicationRequestStatus expected) {
        assertThat(MedicationRequestFhirMapper.statusOf(signedThen(status))).isEqualTo(expected);
        assertThat(new MedicationRequestFhirMapper().toFhir(signedThen(status)).getStatus()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"DRAFT", "PENDING_SIGNATURE"})
    void aProposalIsADraft(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setStatus(status);
        assertThat(MedicationRequestFhirMapper.statusOf(p)).isEqualTo(MedicationRequestStatus.DRAFT);
    }

    @Test
    void aDraftWithdrawnBeforeSigningIsCancelledNeverStopped() {
        Prescription p = new Prescription();
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        assertThat(p.isWithdrawnBeforeSigning()).isTrue();
        assertThat(MedicationRequestFhirMapper.statusOf(p)).isEqualTo(MedicationRequestStatus.CANCELLED);
    }

    @Test
    void aRunningOrderPastItsDurationIsCompleted() {
        Prescription p = signedThen(PrescriptionStatus.SIGNED);
        p.setCreatedAt(LocalDateTime.now().minusDays(20));
        p.setDuration("7 days");
        assertThat(MedicationRequestFhirMapper.statusOf(p)).isEqualTo(MedicationRequestStatus.COMPLETED);
    }
}
