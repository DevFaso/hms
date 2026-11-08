package com.example.hms.fhir.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.service.MedicationCourseStatus;
import org.hl7.fhir.r4.model.MedicationRequest.MedicationRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1537 review (round 40): every MedicationRequest was exported as "active", so a stopped order went
 * out of the FHIR exports as one the patient was still on. Round 2: the status is read from the course
 * status the patient's portal shows (MedicationCourseStatus.of, with the discharged stays), so the two
 * agree.
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
        "PARTNER_ACCEPTED, ACTIVE", "PRINTED_FOR_PATIENT, ACTIVE", "PENDING_CLARIFICATION, ACTIVE",
        "PENDING_STOCK, ACTIVE", "PARTNER_REJECTED, ACTIVE",
        "DISPENSED, ACTIVE", "PARTNER_DISPENSED, ACTIVE",
        "TRANSMISSION_FAILED, ONHOLD",
        "DISCONTINUED, STOPPED", "CANCELLED, CANCELLED"})
    void aSignedOrderCarriesTheStatusThePortalShows(PrescriptionStatus status, MedicationRequestStatus expected) {
        assertThat(MedicationRequestFhirMapper.statusOf(signedThen(status), List.of())).isEqualTo(expected);
        assertThat(new MedicationRequestFhirMapper().toFhir(signedThen(status)).getStatus()).isEqualTo(expected);
    }

    /** Every status agrees with the portal's course status (ACTIVE, COMPLETED, ON_HOLD, DISCONTINUED). */
    @ParameterizedTest
    @EnumSource(PrescriptionStatus.class)
    void everyStatusAgreesWithThePortal(PrescriptionStatus status) {
        Prescription p = signedThen(status);
        String course = MedicationCourseStatus.of(p, List.of());
        MedicationRequestStatus fhir = MedicationRequestFhirMapper.statusOf(p, List.of());
        switch (course) {
            case MedicationCourseStatus.ACTIVE -> assertThat(fhir).isEqualTo(MedicationRequestStatus.ACTIVE);
            case MedicationCourseStatus.COMPLETED -> assertThat(fhir).isEqualTo(MedicationRequestStatus.COMPLETED);
            case MedicationCourseStatus.ON_HOLD -> assertThat(fhir).isIn(MedicationRequestStatus.ONHOLD, MedicationRequestStatus.DRAFT);
            default -> assertThat(fhir).isIn(MedicationRequestStatus.STOPPED, MedicationRequestStatus.CANCELLED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PrescriptionStatus.class, names = {"DRAFT", "PENDING_SIGNATURE"})
    void aProposalIsADraft(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setStatus(status);
        assertThat(MedicationRequestFhirMapper.statusOf(p, List.of())).isEqualTo(MedicationRequestStatus.DRAFT);
    }

    @Test
    void aDraftWithdrawnBeforeSigningIsCancelledNeverStopped() {
        Prescription p = new Prescription();
        p.setStatus(PrescriptionStatus.DISCONTINUED);
        assertThat(p.isWithdrawnBeforeSigning()).isTrue();
        assertThat(MedicationRequestFhirMapper.statusOf(p, List.of())).isEqualTo(MedicationRequestStatus.CANCELLED);
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
        Admission stay = new Admission();
        stay.setAdmissionDateTime(LocalDateTime.now().minusDays(6));
        stay.setActualDischargeDateTime(LocalDateTime.now().minusDays(2));
        List<Admission> closedStays = List.of(stay);

        assertThat(MedicationCourseStatus.of(ward, closedStays)).isEqualTo(MedicationCourseStatus.COMPLETED);
        assertThat(MedicationRequestFhirMapper.statusOf(ward, closedStays)).isEqualTo(MedicationRequestStatus.COMPLETED);
        assertThat(new MedicationRequestFhirMapper().toFhir(ward, closedStays).getStatus())
            .isEqualTo(MedicationRequestStatus.COMPLETED);
        assertThat(MedicationRequestFhirMapper.statusOf(ward, List.of())).isEqualTo(MedicationRequestStatus.ACTIVE);
    }
}
