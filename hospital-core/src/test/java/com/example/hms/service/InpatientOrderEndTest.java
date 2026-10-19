package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D136: when an inpatient order ended with its stay. */
class InpatientOrderEndTest {

    private static final LocalDateTime ADMITTED = LocalDateTime.of(2026, 10, 1, 8, 0);
    private static final LocalDateTime DISCHARGED = LocalDateTime.of(2026, 10, 5, 8, 0);

    private Hospital hospital;
    private Prescription rx;
    private Admission stay;

    @BeforeEach
    void setUp() {
        hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        rx = new Prescription();
        rx.setHospital(hospital);
        rx.setCreatedAt(ADMITTED.plusDays(1));
        stay = new Admission();
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(ADMITTED);
        stay.setActualDischargeDateTime(DISCHARGED);
    }

    @Test
    void anOrderOnAClosedStayEncounterHasEnded() {
        Encounter enc = new Encounter();
        enc.setAdmissionId(UUID.randomUUID());
        enc.setStatus(EncounterStatus.COMPLETED);
        rx.setEncounter(enc);
        rx.setCreatedAt(null);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of())).isTrue();

        enc.setStatus(EncounterStatus.CANCELLED);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of())).isTrue();
    }

    @Test
    void anOrderOnAnOpenStayEncounter_orAnOutpatientEncounter_fallsBackToTheStayWindow() {
        Encounter enc = new Encounter();
        enc.setAdmissionId(UUID.randomUUID());
        enc.setStatus(EncounterStatus.IN_PROGRESS);
        rx.setEncounter(enc);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of())).isFalse();

        enc.setAdmissionId(null);
        enc.setStatus(EncounterStatus.COMPLETED);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isTrue();
    }

    @Test
    void anOrderWrittenDuringAnEndedStayAtItsHospitalHasEnded() {
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isTrue();
    }

    @Test
    void anOrderWrittenOutsideTheStay_orAtAnotherHospital_hasNot() {
        rx.setCreatedAt(ADMITTED.minusHours(1));
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isFalse();
        rx.setCreatedAt(DISCHARGED.plusHours(1));
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isFalse();

        rx.setCreatedAt(ADMITTED.plusDays(1));
        Hospital other = Hospital.builder().build();
        other.setId(UUID.randomUUID());
        rx.setHospital(other);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isFalse();
    }

    @Test
    void anUnknownHospitalOnEitherSideDoesNotBlockTheMatch() {
        rx.setHospital(null);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isTrue();
        rx.setHospital(hospital);
        stay.setHospital(null);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isTrue();
    }

    @Test
    void missingDatesOrStaysNeverEndAnOrder() {
        assertThat(InpatientOrderEnd.endedWithAStay(rx, null)).isFalse();
        Admission open = new Admission();
        open.setAdmissionDateTime(ADMITTED);
        Admission undated = new Admission();
        undated.setActualDischargeDateTime(DISCHARGED);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(open, undated))).isFalse();
        rx.setCreatedAt(null);
        assertThat(InpatientOrderEnd.endedWithAStay(rx, List.of(stay))).isFalse();
    }
}
