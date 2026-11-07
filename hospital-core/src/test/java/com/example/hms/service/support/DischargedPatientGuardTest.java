package com.example.hms.service.support;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA D1477 (round 35): the one rule for who is under nursing care here, on a stay and on a visit. */
class DischargedPatientGuardTest {

    private static final String KEY = "nurse.vitals.patientDischarged";
    private static final LocalDateTime LAST_WEEK = LocalDateTime.of(2026, 11, 1, 9, 0);

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final DischargedPatientGuard guard = new DischargedPatientGuard(admissions, encounters);
    private final UUID patient = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();
    private final List<Admission> stays = new ArrayList<>();
    private final List<Encounter> visits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(admissions.findByPatient_IdAndHospital_IdAndStatusIn(any(), any(), any())).thenReturn(stays);
        when(encounters.findByPatient_IdAndHospital_Id(patient, hospital)).thenReturn(visits);
        when(encounters.findByPatient_IdAndHospital_IdAndStatusNotIn(any(), any(), any()))
            .thenAnswer(call -> visits.stream().filter(v -> !v.getStatus().isClosed()).toList());
    }

    private void visit(EncounterStatus status, LocalDateTime at) {
        Encounter encounter = new Encounter();
        encounter.setStatus(status);
        encounter.setEncounterDate(at);
        visits.add(encounter);
    }

    private void stay(AdmissionStatus status, LocalDateTime dischargedAt) {
        Admission admission = new Admission();
        admission.setStatus(status);
        admission.setActualDischargeDateTime(dischargedAt);
        stays.add(admission);
    }

    private void assertRefused() {
        assertThatThrownBy(() -> guard.requireNotDischargedWithoutVisit(patient, hospital, KEY))
            .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", KEY);
    }

    private void assertAllowed() {
        assertThatCode(() -> guard.requireNotDischargedWithoutVisit(patient, hospital, KEY)).doesNotThrowAnyException();
    }

    @Test
    void aPatientWithNoStayAndNoVisitIsAllowed() {
        assertAllowed();
    }

    @Test
    void aLastVisitEndedUnseenIsRefused() {
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK);
        assertRefused();
    }

    @Test
    void aLastVisitCancelledIsRefused() {
        visit(EncounterStatus.CANCELLED, LAST_WEEK);
        assertRefused();
    }

    @Test
    void aVisitThatEndedWithAConsultationStaysOpenToTheNurse() {
        visit(EncounterStatus.COMPLETED, LAST_WEEK);
        assertAllowed();
    }

    @Test
    void aLaterConsultationOutweighsAnOlderUnseenVisit() {
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK.minusDays(5));
        visit(EncounterStatus.COMPLETED, LAST_WEEK);
        assertAllowed();
    }

    @Test
    void aPrescriptionAnchorIsNotAVisit() {
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK);
        visit(EncounterStatus.COMPLETED, LAST_WEEK.plusDays(1));
        visits.get(1).setNotes(Encounter.PRESCRIPTION_ANCHOR_NOTE);
        assertRefused();
    }

    @Test
    void aVisitOpenedSinceIsCare() {
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK);
        visit(EncounterStatus.ARRIVED, LAST_WEEK.plusDays(1));
        assertAllowed();
    }

    @Test
    void aVisitWithNoDateAtAllIsNotReadAsTheLast() {
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK);
        visit(EncounterStatus.COMPLETED, null);
        assertRefused();
    }

    @Test
    void aDischargedStayAndACancelledStayAreRefused() {
        stay(AdmissionStatus.DISCHARGED, LAST_WEEK);
        assertRefused();
        stays.clear();
        stay(AdmissionStatus.CANCELLED, null);
        assertRefused();
    }

    @Test
    void anOpenStayIsCareWhateverTheVisits() {
        stay(AdmissionStatus.ACTIVE, null);
        visit(EncounterStatus.LEFT_WITHOUT_BEING_SEEN, LAST_WEEK);
        assertAllowed();
    }
}
