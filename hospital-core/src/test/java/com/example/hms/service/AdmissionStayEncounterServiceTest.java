package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D98/D135 (the stay opens its encounter) and D151 (the discharge reaches it). */
@ExtendWith(MockitoExtension.class)
class AdmissionStayEncounterServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private com.example.hms.repository.NurseHandoffRepository nurseHandoffRepository;
    @Mock private com.example.hms.repository.NursingTaskRepository nursingTaskRepository;
    @Mock private org.springframework.context.MessageSource messageSource;
    @Mock private com.example.hms.repository.DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private com.example.hms.repository.PrescriptionRepository prescriptionRepository;
    @Mock private StayOrderDiscontinuation stayOrders;

    @InjectMocks private AdmissionStayEncounterService service;

    private Hospital hospital;
    private Staff doctor;
    private Admission admission;

    @BeforeEach
    void setUp() {
        hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());
        doctor = new Staff();
        doctor.setId(UUID.randomUUID());
        doctor.setUser(user);
        doctor.setHospital(hospital);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        admission.setPatient(patient);
        admission.setAdmittingProvider(doctor);
        admission.setAdmissionDateTime(LocalDateTime.of(2026, 10, 18, 9, 30));
        admission.setChiefComplaint("Fièvre");
    }

    @Test
    void anAdmissionOpensAnInpatientEncounterTaggedWithItsId() {
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(assignment));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

        Encounter stay = service.openStayEncounter(admission);

        assertThat(stay.getAdmissionId()).isEqualTo(admission.getId());
        assertThat(stay.getEncounterType()).isEqualTo(EncounterType.INPATIENT);
        assertThat(stay.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        assertThat(stay.getStaff()).isSameAs(doctor);
        assertThat(stay.getAssignment()).isSameAs(assignment);
        assertThat(stay.getEncounterDate()).isEqualTo(admission.getAdmissionDateTime());
        assertThat(stay.getChiefComplaint()).isEqualTo("Fièvre");
        assertThat(stay.getCode()).startsWith("ENC-");
    }

    @Test
    void anAttendingWithNoRoleAtTheHospital_fallsBackToTheAdmittingProvider() {
        User attendingUser = new User();
        attendingUser.setId(UUID.randomUUID());
        Staff attending = new Staff();
        attending.setId(UUID.randomUUID());
        attending.setUser(attendingUser);
        attending.setHospital(hospital);
        admission.setAttendingPhysician(attending);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
        when(assignmentRepository.findActiveStaffAssignment(attendingUser.getId(), hospital.getId()))
            .thenReturn(Optional.empty());
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(assignment));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

        Encounter stay = service.openStayEncounter(admission);

        assertThat(stay.getStaff()).isSameAs(doctor);
        assertThat(stay.getAssignment()).isSameAs(assignment);
    }

    @Test
    void noLeadWithARoleAtTheHospital_stillAdmits_withoutAStayEncounter() {
        // Before stays had encounters this admission succeeded; it must not
        // be refused now (#894 self-review).
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.empty());

        assertThat(service.openStayEncounter(admission)).isNull();
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void aStatedDischargeDiagnosisWinsOverTheWorkingDiagnosis_D218() {
        Encounter stay = new Encounter();
        stay.setAdmissionId(admission.getId());
        admission.setPrimaryDiagnosisDescription("Fièvre");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        service.recordDischarge(admission, "Paludisme grave");

        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getDischargeDiagnoses()).isEqualTo("[\"Paludisme grave\"]");
    }

    @Test
    void theDischargeClosesThePatientsPendingHandoffs_D234() {
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.empty());
        com.example.hms.model.NurseHandoff open = new com.example.hms.model.NurseHandoff();
        open.setStatus("PENDING");
        when(nurseHandoffRepository.findByPatient_IdAndHospital_IdAndStatus(
            admission.getPatient().getId(), hospital.getId(), "PENDING")).thenReturn(List.of(open));
        when(messageSource.getMessage(eq("nurse.handoff.closedByDischarge"), any(), any(), any()))
            .thenReturn("Clôturée à la sortie");

        service.recordDischarge(admission);

        assertThat(open.getStatus()).isEqualTo("COMPLETED");
        assertThat(open.getCompletedAt()).isNotNull();
        assertThat(open.getCompletedByName()).isEqualTo("Clôturée à la sortie");
        verify(nurseHandoffRepository).saveAll(List.of(open));
    }

    @Test
    void theDischargeClosesThePatientsPendingNursingTasks_D294() {
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.empty());
        com.example.hms.model.NursingTask open = new com.example.hms.model.NursingTask();
        open.setStatus("PENDING");
        when(nursingTaskRepository.findByPatient_IdAndHospital_IdAndStatus(
            admission.getPatient().getId(), hospital.getId(), "PENDING")).thenReturn(List.of(open));
        when(messageSource.getMessage(eq("nurse.handoff.closedByDischarge"), any(), any(), any()))
            .thenReturn("Clôturée à la sortie");

        service.recordDischarge(admission);

        assertThat(open.getStatus()).isEqualTo("COMPLETED");
        assertThat(open.getCompletedAt()).isNotNull();
        assertThat(open.getCompletedByName()).isEqualTo("Clôturée à la sortie");
        verify(nursingTaskRepository).saveAll(List.of(open));
    }

    @Test
    void theDischargeIsCarriedOntoTheStayEncounter() {
        Encounter stay = new Encounter();
        stay.setAdmissionId(admission.getId());
        admission.setDischargeInstructions("Revoir dans 7 jours");
        admission.setPrimaryDiagnosisDescription("Paludisme \"grave\"");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        service.recordDischarge(admission);

        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getFollowUpInstructions()).isEqualTo("Revoir dans 7 jours");
        assertThat(saved.getValue().getDischargeDiagnoses()).isEqualTo("[\"Paludisme \\\"grave\\\"\"]");
    }

    @Test
    void noHospital_noStayEncounter() {
        admission.setHospital(null);

        assertThat(service.openStayEncounter(admission)).isNull();
        verify(encounterRepository, never()).save(any());
    }

    @Test
    void aLeadFromAnotherHospital_usesTheirStaffRowHere_andAForeignDepartmentIsDropped() {
        Hospital other = Hospital.builder().build();
        other.setId(UUID.randomUUID());
        doctor.setHospital(other);
        Staff here = new Staff();
        here.setId(UUID.randomUUID());
        here.setUser(doctor.getUser());
        here.setHospital(hospital);
        Department foreign = new Department();
        foreign.setHospital(other);
        admission.setDepartment(foreign);
        admission.setAdmissionDateTime(null);
        when(staffRepository.findByUserIdAndHospitalId(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(here));
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(new UserRoleHospitalAssignment()));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

        Encounter stay = service.openStayEncounter(admission);

        assertThat(stay.getStaff()).isSameAs(here);
        assertThat(stay.getDepartment()).isNull();
        assertThat(stay.getEncounterDate()).isNotNull();
    }

    @Test
    void aDepartmentOfTheSameHospitalIsKept() {
        Department ward = new Department();
        ward.setHospital(hospital);
        admission.setDepartment(ward);
        when(assignmentRepository.findActiveStaffAssignment(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.of(new UserRoleHospitalAssignment()));
        when(encounterRepository.save(any(Encounter.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.openStayEncounter(admission).getDepartment()).isSameAs(ward);
    }

    @Test
    void anAttendingWithNoUser_andAProviderWithNoStaffRowHere_isNoLead() {
        Hospital other = Hospital.builder().build();
        other.setId(UUID.randomUUID());
        doctor.setHospital(other);
        admission.setAttendingPhysician(new Staff());
        when(staffRepository.findByUserIdAndHospitalId(doctor.getUser().getId(), hospital.getId()))
            .thenReturn(Optional.empty());

        assertThat(service.openStayEncounter(admission)).isNull();
    }

    @Test
    void theDischargeNeverOverwritesWhatTheEncounterAlreadySays() {
        Encounter stay = new Encounter();
        stay.setFollowUpInstructions("deja");
        stay.setNotes("deja");
        stay.setDischargeDiagnoses("[\"x\"]");
        admission.setDischargeInstructions("autre");
        admission.setDischargeSummary("autre");
        admission.setPrimaryDiagnosisDescription("autre");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        service.recordDischarge(admission);

        assertThat(stay.getFollowUpInstructions()).isEqualTo("deja");
        assertThat(stay.getNotes()).isEqualTo("deja");
        assertThat(stay.getDischargeDiagnoses()).isEqualTo("[\"x\"]");
    }

    @Test
    void aLongDischargeSummaryIsTruncatedIntoTheNotes_andBlankSourcesFillNothing() {
        Encounter stay = new Encounter();
        admission.setDischargeSummary("a".repeat(3000));
        admission.setDischargeInstructions(" ");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        service.recordDischarge(admission);

        assertThat(stay.getNotes()).hasSize(2048);
        assertThat(stay.getFollowUpInstructions()).isNull();
        assertThat(stay.getDischargeDiagnoses()).isNull();
    }

    @Test
    void anUnsavedAdmissionHasNoStayToDischarge() {
        admission.setId(null);

        service.recordDischarge(admission);

        verify(encounterRepository, never()).findFirstByAdmissionIdOrderByEncounterDateDesc(any());
    }

    /* ── D426: a discharged stay records its discharge summary ──────────── */

    @Test
    void d426_theDischargeWritesASummaryWithTheStaysMedicinesAndFollowUps() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(admission.getId());
        stay.setPatient(admission.getPatient());
        stay.setHospital(hospital);
        stay.setStaff(doctor);
        stay.setAssignment(new UserRoleHospitalAssignment());
        admission.setDischargeInstructions("Repos, revoir dans 2 semaines");
        admission.setFollowUpAppointments(List.of(java.util.Map.of(
            "appointmentType", "CONSULTATION", "appointmentDate", "2026-11-03", "purpose", "Contrôle")));
        com.example.hms.model.Prescription amox = new com.example.hms.model.Prescription();
        amox.setMedicationName("Amoxicilline");
        amox.setDosage("1 g");
        amox.setFrequency("3x/j");
        amox.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        // QA D757: only an order marked for home goes on the summary.
        amox.setTakeHome(true);
        amox.setId(UUID.randomUUID());
        com.example.hms.model.Prescription ward = new com.example.hms.model.Prescription();
        ward.setMedicationName("Paracétamol");
        ward.setStatus(com.example.hms.enums.PrescriptionStatus.SIGNED);
        com.example.hms.model.Prescription draft = new com.example.hms.model.Prescription();
        draft.setMedicationName("Brouillon");
        draft.setStatus(com.example.hms.enums.PrescriptionStatus.DRAFT);
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));
        when(dischargeSummaryRepository.existsByEncounter_Id(stay.getId())).thenReturn(false);
        when(prescriptionRepository.findByEncounter_Id(eq(stay.getId()), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(amox, ward, draft)));

        service.recordDischarge(admission, "Pneumopathie");

        ArgumentCaptor<com.example.hms.model.discharge.DischargeSummary> saved =
            ArgumentCaptor.forClass(com.example.hms.model.discharge.DischargeSummary.class);
        verify(dischargeSummaryRepository).save(saved.capture());
        com.example.hms.model.discharge.DischargeSummary summary = saved.getValue();
        assertThat(summary.getEncounter()).isSameAs(stay);
        assertThat(summary.getDischargeDiagnosis()).isEqualTo("Pneumopathie");
        assertThat(summary.getFollowUpInstructions()).isEqualTo("Repos, revoir dans 2 semaines");
        assertThat(summary.getMedicationReconciliation())
            .extracting(com.example.hms.model.discharge.MedicationReconciliationEntry::getMedicationName)
            .containsExactly("Amoxicilline");
        assertThat(summary.getMedicationReconciliation().get(0).getPrescriptionId()).isEqualTo(amox.getId());
        // QA D757: the ward orders were ended before the summary was written.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stayOrders, dischargeSummaryRepository);
        order.verify(stayOrders).discontinueStayOrders(admission, null);
        order.verify(dischargeSummaryRepository).save(any());
        assertThat(summary.getFollowUpAppointments())
            .extracting(com.example.hms.model.discharge.FollowUpAppointmentEntry::getAppointmentDate)
            .containsExactly(java.time.LocalDate.of(2026, 11, 3));
    }

    @Test
    void d426_aSummaryAlreadyWrittenOnTheDischargeScreenIsKept() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(admission.getId());
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));
        when(dischargeSummaryRepository.existsByEncounter_Id(stay.getId())).thenReturn(true);

        service.recordDischarge(admission, "Pneumopathie");

        verify(dischargeSummaryRepository, never()).save(any());
    }

    @Test
    void d426_followUpsKeepOnlyUsableRowsAndReadADateTime() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(admission.getId());
        stay.setPatient(admission.getPatient());
        stay.setHospital(hospital);
        stay.setStaff(doctor);
        stay.setAssignment(new UserRoleHospitalAssignment());
        java.util.List<java.util.Map<String, Object>> followUps = new java.util.ArrayList<>();
        followUps.add(null);
        followUps.add(java.util.Map.of("notes", " "));
        followUps.add(java.util.Map.of("date", "2026-11-03T10:00:00", "provider", "Dr Kone", "specialty", "Pneumologie"));
        followUps.add(java.util.Map.of("type", "CONTROLE", "date", "bientot"));
        admission.setFollowUpAppointments(followUps);
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));
        when(dischargeSummaryRepository.existsByEncounter_Id(stay.getId())).thenReturn(false);
        when(prescriptionRepository.findByEncounter_Id(eq(stay.getId()), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.recordDischarge(admission, "Pneumopathie");

        ArgumentCaptor<com.example.hms.model.discharge.DischargeSummary> saved =
            ArgumentCaptor.forClass(com.example.hms.model.discharge.DischargeSummary.class);
        verify(dischargeSummaryRepository).save(saved.capture());
        assertThat(saved.getValue().getFollowUpAppointments()).hasSize(2);
        com.example.hms.model.discharge.FollowUpAppointmentEntry first = saved.getValue().getFollowUpAppointments().get(0);
        assertThat(first.getAppointmentDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 3));
        assertThat(first.getProviderName()).isEqualTo("Dr Kone");
        com.example.hms.model.discharge.FollowUpAppointmentEntry second = saved.getValue().getFollowUpAppointments().get(1);
        assertThat(second.getAppointmentDate()).isNull();
        assertThat(second.getAppointmentType()).isEqualTo("CONTROLE");
    }

    @Test
    void d426_noProviderMeansNoSummaryRatherThanAnInvalidOne() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(admission.getId());
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));
        when(dischargeSummaryRepository.existsByEncounter_Id(stay.getId())).thenReturn(false);

        service.recordDischarge(admission, "Pneumopathie");

        verify(dischargeSummaryRepository, never()).save(any());
    }

    // ── QA D803 / D804 (round 13) ───────────────────────────────────────

    private Department department(String name, Hospital owner) {
        Department department = new Department();
        department.setId(UUID.randomUUID());
        department.setName(name);
        department.setHospital(owner);
        return department;
    }

    private Encounter openStayIn(Department department) {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(admission.getId());
        stay.setHospital(hospital);
        stay.setDepartment(department);
        stay.setStatus(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));
        return stay;
    }

    @Test
    void d803_theQaCall_theDischargeDiagnosisIsOnTheAdmissionResponseAfterDischarge() {
        Encounter stay = openStayIn(null);

        service.recordDischarge(admission, "J18.9 Pneumopathie, sans précision");
        com.example.hms.payload.dto.AdmissionResponseDTO dto =
            new com.example.hms.mapper.AdmissionMapper(encounterRepository).toResponseDTO(admission);

        assertThat(stay.getDischargeDiagnoses()).isEqualTo("[\"J18.9 Pneumopathie, sans précision\"]");
        assertThat(dto.getDischargeDiagnosis()).isEqualTo("J18.9 Pneumopathie, sans précision");
    }

    @Test
    void d803_aDischargeWithNoStatedDiagnosisShowsTheWorkingOneTheSummaryShows() {
        Encounter stay = openStayIn(null);
        admission.setPrimaryDiagnosisDescription("Paludisme grave");

        service.recordDischarge(admission);
        com.example.hms.payload.dto.AdmissionResponseDTO dto =
            new com.example.hms.mapper.AdmissionMapper(encounterRepository).toResponseDTO(admission);

        assertThat(dto.getDischargeDiagnosis()).isEqualTo("Paludisme grave");
        assertThat(stay.getDischargeDiagnoses()).isEqualTo("[\"Paludisme grave\"]");
    }

    @Test
    void d804_theQaCall_aTransferThenTheDischargeCloseTheEncounterInTheNewDepartment() {
        Department admitting = department("Médecine générale", hospital);
        Department cardiology = department("Cardiologie", hospital);
        admission.setDepartment(admitting);
        Encounter stay = openStayIn(admitting);

        // the transfer: BedAssignmentService / updateAdmission move the admission
        admission.setDepartment(cardiology);
        service.followStayDepartment(admission);
        assertThat(stay.getDepartment()).isSameAs(cardiology);

        service.recordDischarge(admission, "J18.9 Pneumopathie, sans précision");
        assertThat(stay.getDepartment()).isSameAs(cardiology);
    }

    @Test
    void d804_theDischargeAloneCarriesAMoveNoHookSaw() {
        Department admitting = department("Médecine générale", hospital);
        Department cardiology = department("Cardiologie", hospital);
        Encounter stay = openStayIn(admitting);
        admission.setDepartment(cardiology);

        service.recordDischarge(admission, "Insuffisance cardiaque");

        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getDepartment()).isSameAs(cardiology);
        assertThat(stay.getDepartment()).isSameAs(cardiology);
    }

    @Test
    void d804_aClosedEncounterIsNotMovedByALaterEdit_andTheSameDepartmentWritesNothing() {
        Department admitting = department("Médecine générale", hospital);
        Encounter stay = openStayIn(admitting);
        admission.setDepartment(admitting);

        service.followStayDepartment(admission);
        verify(encounterRepository, never()).save(any());

        stay.setStatus(EncounterStatus.COMPLETED);
        admission.setDepartment(department("Cardiologie", hospital));
        service.followStayDepartment(admission);
        verify(encounterRepository, never()).save(any());
        assertThat(stay.getDepartment()).isSameAs(admitting);
    }

    @Test
    void d804_anotherHospitalsDepartmentNeverReachesTheEncounter() {
        Department admitting = department("Médecine générale", hospital);
        Hospital other = Hospital.builder().build();
        other.setId(UUID.randomUUID());
        Encounter stay = openStayIn(admitting);
        admission.setDepartment(department("Cardiologie", other));

        service.followStayDepartment(admission);

        verify(encounterRepository, never()).save(any());
        assertThat(stay.getDepartment()).isSameAs(admitting);
    }
}
