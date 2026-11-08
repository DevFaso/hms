package com.example.hms.fhir.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.service.MedicationCourseStatus;
import org.hl7.fhir.r4.model.MedicationRequest.MedicationRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 review (round 40): every MedicationRequest was exported as "active", so a stopped order went
 * out of the FHIR exports as one the patient was still on. Each PrescriptionStatus is mapped here (round 3:
 * an order held for review or rejected by a partner is not in force: on-hold), and a running order is
 * completed when the course status the portal shows says so.
 */
class MedicationRequestStatusMappingTest {

    /** Every status, decided once: the test fails when a status is added and not listed. */
    private static final Map<PrescriptionStatus, MedicationRequestStatus> EXPECTED = new EnumMap<>(Map.ofEntries(
        Map.entry(PrescriptionStatus.DRAFT, MedicationRequestStatus.DRAFT),
        Map.entry(PrescriptionStatus.PENDING_SIGNATURE, MedicationRequestStatus.DRAFT),
        Map.entry(PrescriptionStatus.SIGNED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.TRANSMITTED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.TRANSMISSION_FAILED, MedicationRequestStatus.ONHOLD),
        Map.entry(PrescriptionStatus.CANCELLED, MedicationRequestStatus.CANCELLED),
        Map.entry(PrescriptionStatus.DISCONTINUED, MedicationRequestStatus.STOPPED),
        Map.entry(PrescriptionStatus.PENDING_CLARIFICATION, MedicationRequestStatus.ONHOLD),
        Map.entry(PrescriptionStatus.DISPENSED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.PARTIALLY_FILLED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.PENDING_STOCK, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.SENT_TO_PARTNER, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.PARTNER_ACCEPTED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.PARTNER_REJECTED, MedicationRequestStatus.ONHOLD),
        Map.entry(PrescriptionStatus.PARTNER_DISPENSED, MedicationRequestStatus.ACTIVE),
        Map.entry(PrescriptionStatus.PRINTED_FOR_PATIENT, MedicationRequestStatus.ACTIVE)));

    private static Prescription signedThen(PrescriptionStatus status) {
        Prescription p = new Prescription();
        if (!status.isProposal()) {
            p.setStatus(PrescriptionStatus.SIGNED);
        }
        p.setStatus(status);
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    @Test
    void everyStatusIsDecided() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(PrescriptionStatus.values());
    }

    @ParameterizedTest
    @EnumSource(PrescriptionStatus.class)
    void eachStatusIsMapped(PrescriptionStatus status) {
        assertThat(MedicationRequestFhirMapper.statusOf(signedThen(status), List.of())).isEqualTo(EXPECTED.get(status));
        assertThat(new MedicationRequestFhirMapper().toFhir(signedThen(status)).getStatus()).isEqualTo(EXPECTED.get(status));
    }

    /** Not in force is never exported as active: held for review, rejected by a partner, failed to send. */
    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"PENDING_CLARIFICATION", "PARTNER_REJECTED", "TRANSMISSION_FAILED"})
    void anOrderNotInForceIsOnHold(PrescriptionStatus status) {
        assertThat(MedicationRequestFhirMapper.statusOf(signedThen(status), List.of())).isEqualTo(MedicationRequestStatus.ONHOLD);
    }

    /** A dispensed order runs until its stated duration ends, as on the portal; then it is completed. */
    @Test
    void aDispensedOrderIsCompletedWhenItsDurationHasPassed() {
        Prescription running = signedThen(PrescriptionStatus.DISPENSED);
        running.setDuration("7 days");
        assertThat(MedicationRequestFhirMapper.statusOf(running, List.of())).isEqualTo(MedicationRequestStatus.ACTIVE);
        Prescription ended = signedThen(PrescriptionStatus.DISPENSED);
        ended.setCreatedAt(LocalDateTime.now().minusDays(20));
        ended.setDuration("7 days");
        assertThat(MedicationRequestFhirMapper.statusOf(ended, List.of())).isEqualTo(MedicationRequestStatus.COMPLETED);
    }

    /** An inpatient order ends with its discharged stay (InpatientOrderEnd), as on the portal. */
    @Test
    void anOrderThatEndedWithItsStayIsCompleted() {
        Prescription ward = signedThen(PrescriptionStatus.SIGNED);
        ward.setCreatedAt(LocalDateTime.now().minusDays(5));
        Admission stay = new com.example.hms.repository.ClosedStayRow(java.util.UUID.randomUUID(),
            LocalDateTime.now().minusDays(6), LocalDateTime.now().minusDays(2), null,
            com.example.hms.enums.AdmissionStatus.DISCHARGED).toAdmission();
        List<Admission> closedStays = List.of(stay);

        assertThat(MedicationCourseStatus.of(ward, closedStays)).isEqualTo(MedicationCourseStatus.COMPLETED);
        assertThat(MedicationRequestFhirMapper.statusOf(ward, closedStays)).isEqualTo(MedicationRequestStatus.COMPLETED);
        assertThat(new MedicationRequestFhirMapper().toFhir(ward, closedStays).getStatus())
            .isEqualTo(MedicationRequestStatus.COMPLETED);
        assertThat(MedicationRequestFhirMapper.statusOf(ward, List.of())).isEqualTo(MedicationRequestStatus.ACTIVE);
    }
}
