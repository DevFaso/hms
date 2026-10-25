package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.repository.AdmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA D792 (#953 review): the guards refused only an order on a closed stay
 * encounter, so the orders V233 holds for a prepared fill or an open partner
 * routing in any other shape (an open stay encounter on a discharged
 * admission, an unlinked INPATIENT encounter, an emergency carry-over) could
 * still be handed over, claimed, routed or sent by SMS. The guard applies the
 * V233 rule to every shape.
 */
class EndedStayOrderGuardTest {

    private static final LocalDateTime ADMITTED = LocalDateTime.of(2026, 10, 24, 4, 0);
    private static final LocalDateTime DISCHARGED = LocalDateTime.of(2026, 10, 24, 4, 43);

    private AdmissionRepository admissions;
    private EndedStayOrderGuard guard;
    private Hospital hospital;
    private Patient patient;
    private Admission stay;
    private Prescription rx;

    @BeforeEach
    void setUp() {
        admissions = mock(AdmissionRepository.class);
        guard = new EndedStayOrderGuard(admissions);
        hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        stay = new Admission();
        stay.setId(UUID.randomUUID());
        stay.setHospital(hospital);
        stay.setStatus(AdmissionStatus.DISCHARGED);
        stay.setAdmissionDateTime(ADMITTED);
        stay.setActualDischargeDateTime(DISCHARGED);
        rx = new Prescription();
        rx.setPatient(patient);
        rx.setHospital(hospital);
        rx.setCreatedAt(ADMITTED.plusMinutes(15));
        when(admissions.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId())).thenReturn(List.of(stay));
    }

    @Test
    @DisplayName("an in-memory order with no encounter follows the discharge rule's time window (orderOfStay parity)")
    void noEncounterDuringTheStay() {
        assertThat(guard.endedWithAStay(rx)).isTrue();

        rx.setCreatedAt(DISCHARGED.plusMinutes(30));
        assertThat(guard.endedWithAStay(rx)).as("written after the discharge").isFalse();
        rx.setCreatedAt(ADMITTED.minusMinutes(30));
        assertThat(guard.endedWithAStay(rx)).as("written before the admission").isFalse();
    }

    @Test
    @DisplayName("an order on a stay's encounter left open after its admission was discharged has ended")
    void openStayEncounterOfADischargedAdmission() {
        Encounter enc = encounter(EncounterType.INPATIENT, EncounterStatus.IN_PROGRESS);
        enc.setAdmissionId(stay.getId());
        rx.setEncounter(enc);
        rx.setCreatedAt(DISCHARGED.plusHours(2));
        assertThat(guard.endedWithAStay(rx)).isTrue();

        stay.setStatus(AdmissionStatus.ACTIVE);
        stay.setActualDischargeDateTime(null);
        assertThat(guard.endedWithAStay(rx)).as("its stay is still in progress").isFalse();
    }

    @Test
    @DisplayName("an unlinked INPATIENT encounter's order written during an ended stay has ended")
    void unlinkedInpatientEncounter() {
        rx.setEncounter(encounter(EncounterType.INPATIENT, EncounterStatus.IN_PROGRESS));
        assertThat(guard.endedWithAStay(rx)).isTrue();
    }

    @Test
    @DisplayName("an emergency order carried over to an ended stay has ended; one of a visit checked out before it has not")
    void emergencyCarryOver() {
        Encounter ed = encounter(EncounterType.EMERGENCY, EncounterStatus.IN_PROGRESS);
        rx.setEncounter(ed);
        rx.setCreatedAt(ADMITTED.minusHours(2));
        assertThat(guard.endedWithAStay(rx)).isTrue();

        ed.setCheckoutTimestamp(ADMITTED.minusHours(1));
        assertThat(guard.endedWithAStay(rx)).as("checked out before the admission").isFalse();

        ed.setCheckoutTimestamp(null);
        rx.setCreatedAt(ADMITTED.minusHours(25));
        assertThat(guard.endedWithAStay(rx)).as("more than 24 hours before the admission").isFalse();
    }

    @Test
    @DisplayName("a take-home order, an outpatient order and an order of a stay in progress are not refused")
    void notEnded() {
        rx.setTakeHome(true);
        assertThat(guard.endedWithAStay(rx)).isFalse();
        rx.setTakeHome(false);

        rx.setEncounter(encounter(EncounterType.CONSULTATION, EncounterStatus.COMPLETED));
        assertThat(guard.endedWithAStay(rx)).isFalse();

        rx.setEncounter(null);
        stay.setActualDischargeDateTime(null);
        stay.setStatus(AdmissionStatus.ACTIVE);
        assertThat(guard.endedWithAStay(rx)).isFalse();
    }

    @Test
    @DisplayName("an order on a closed stay encounter is answered from the order alone")
    void closedStayEncounterNeedsNoRead() {
        AdmissionRepository untouched = mock(AdmissionRepository.class);
        Encounter enc = encounter(EncounterType.INPATIENT, EncounterStatus.COMPLETED);
        enc.setAdmissionId(UUID.randomUUID());
        rx.setEncounter(enc);
        assertThat(new EndedStayOrderGuard(untouched).endedWithAStay(rx)).isTrue();
        verifyNoInteractions(untouched);
    }

    @Test
    @DisplayName("a stay at another hospital never ends the order")
    void anotherHospitalsStay() {
        Hospital other = Hospital.builder().build();
        other.setId(UUID.randomUUID());
        stay.setHospital(other);
        assertThat(InpatientOrderEnd.endedWithAnEndedStay(rx, List.of(stay))).isFalse();
    }

    private static Encounter encounter(EncounterType type, EncounterStatus status) {
        Encounter enc = new Encounter();
        enc.setEncounterType(type);
        enc.setStatus(status);
        return enc;
    }
}
