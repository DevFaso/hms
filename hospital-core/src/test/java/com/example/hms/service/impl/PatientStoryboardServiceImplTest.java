package com.example.hms.service.impl;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;
import com.example.hms.repository.AdvanceDirectiveRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatientStoryboardServiceImplTest {

    private final PatientRepository patientRepo = mock(PatientRepository.class);
    private final PatientAllergyRepository allergyRepo = mock(PatientAllergyRepository.class);
    private final PatientProblemRepository problemRepo = mock(PatientProblemRepository.class);
    private final EncounterRepository encounterRepo = mock(EncounterRepository.class);
    private final AdvanceDirectiveRepository directiveRepo = mock(AdvanceDirectiveRepository.class);
    private final HospitalRepository hospitalRepo = mock(HospitalRepository.class);

    private PatientStoryboardServiceImpl service;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new PatientStoryboardServiceImpl(
            patientRepo, allergyRepo, problemRepo, encounterRepo, directiveRepo, hospitalRepo);

        hospital = Hospital.builder().name("Centre Médical Bobo").build();
        hospital.setId(HOSPITAL_ID);

        patient = buildPatient("MRN-1001", "FULL_CODE");

        when(patientRepo.findById(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(hospitalRepo.findById(HOSPITAL_ID)).thenReturn(Optional.of(hospital));
    }

    @Test
    void missingPatientThrowsNotFound() {
        when(patientRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        UUID missingId = UUID.randomUUID();
        assertThatThrownBy(() -> service.getStoryboard(missingId, HOSPITAL_ID))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aggregatesActiveDataAndFlagsHighSeverityAllergy() {
        PatientAllergy mild = allergy("Sulfa", AllergySeverity.MILD, true);
        PatientAllergy lifeThreat = allergy("Penicillin", AllergySeverity.LIFE_THREATENING, true);
        PatientAllergy inactive = allergy("Latex", AllergySeverity.SEVERE, false);
        when(allergyRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(mild, lifeThreat, inactive));

        PatientProblem chronic = problem("Sickle cell disease", ProblemStatus.ACTIVE, true);
        PatientProblem acute = problem("Malaria — uncomplicated", ProblemStatus.ACTIVE, false);
        PatientProblem resolved = problem("Otitis media", ProblemStatus.RESOLVED, false);
        when(problemRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(acute, chronic, resolved));

        Encounter enc = encounter(EncounterStatus.IN_PROGRESS, LocalDateTime.now().minusHours(2));
        Encounter completed = encounter(EncounterStatus.COMPLETED, LocalDateTime.now().minusDays(3));
        when(encounterRepo.findByPatient_IdAndHospital_IdAndStatusNotIn(
            eq(PATIENT_ID), eq(HOSPITAL_ID), any()))
            .thenReturn(List.of(enc, completed));

        AdvanceDirective dnr = directive(AdvanceDirectiveType.DO_NOT_RESUSCITATE, AdvanceDirectiveStatus.ACTIVE);
        when(directiveRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID))
            .thenReturn(List.of(dnr));

        PatientStoryboardDTO dto = service.getStoryboard(PATIENT_ID, HOSPITAL_ID);

        assertThat(dto.getPatient().getMrn()).isEqualTo("MRN-1001");
        assertThat(dto.getPatient().getFullName()).isEqualTo("Aïssata Diallo");
        assertThat(dto.getPatient().getAgeYears()).isNotNull().isPositive();

        assertThat(dto.getAllergies()).hasSize(2);
        // High-severity allergy ranked first
        assertThat(dto.getAllergies().get(0).getAllergenDisplay()).isEqualTo("Penicillin");
        assertThat(dto.isHasHighSeverityAllergy()).isTrue();

        assertThat(dto.getProblems()).hasSize(2);
        // Chronic problem ranked first
        assertThat(dto.getProblems().get(0).getProblemDisplay()).isEqualTo("Sickle cell disease");
        assertThat(dto.isHasChronicProblem()).isTrue();

        assertThat(dto.getActiveEncounter()).isNotNull();
        assertThat(dto.getActiveEncounter().getStatus()).isEqualTo("IN_PROGRESS");

        assertThat(dto.getCodeStatus()).isNotNull();
        assertThat(dto.getCodeStatus().getStatus()).isEqualTo("FULL_CODE");
        assertThat(dto.getCodeStatus().getDirectives()).hasSize(1);
        assertThat(dto.getCodeStatus().getDirectives().get(0).getDirectiveType())
            .isEqualTo("DO_NOT_RESUSCITATE");

        assertThat(dto.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(dto.getHospitalName()).isEqualTo("Centre Médical Bobo");
    }

    @Test
    void emptyChartRendersWithoutCrashing() {
        when(allergyRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());
        when(problemRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());
        when(encounterRepo.findByPatient_IdAndHospital_IdAndStatusNotIn(
            eq(PATIENT_ID), eq(HOSPITAL_ID), any())).thenReturn(List.of());
        when(directiveRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());
        // Patient with no code status either
        Patient bare = buildPatient("MRN-9999", null);
        when(patientRepo.findById(PATIENT_ID)).thenReturn(Optional.of(bare));

        PatientStoryboardDTO dto = service.getStoryboard(PATIENT_ID, HOSPITAL_ID);

        assertThat(dto.getAllergies()).isEmpty();
        assertThat(dto.getProblems()).isEmpty();
        assertThat(dto.getActiveEncounter()).isNull();
        assertThat(dto.getCodeStatus()).isNull();
        assertThat(dto.isHasHighSeverityAllergy()).isFalse();
        assertThat(dto.isHasChronicProblem()).isFalse();
    }

    @Test
    void capsAllergyAndProblemListsToProtectMobileBandwidth() {
        java.util.List<PatientAllergy> many = new java.util.ArrayList<>();
        for (int i = 0; i < PatientStoryboardServiceImpl.MAX_ALLERGIES + 4; i++) {
            many.add(allergy("Allergen " + i, AllergySeverity.MILD, true));
        }
        when(allergyRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(many);

        java.util.List<PatientProblem> manyProblems = new java.util.ArrayList<>();
        for (int i = 0; i < PatientStoryboardServiceImpl.MAX_PROBLEMS + 4; i++) {
            manyProblems.add(problem("Problem " + i, ProblemStatus.ACTIVE, false));
        }
        when(problemRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(manyProblems);

        when(encounterRepo.findByPatient_IdAndHospital_IdAndStatusNotIn(
            eq(PATIENT_ID), eq(HOSPITAL_ID), any())).thenReturn(List.of());
        when(directiveRepo.findByPatient_IdAndHospital_Id(PATIENT_ID, HOSPITAL_ID)).thenReturn(List.of());

        PatientStoryboardDTO dto = service.getStoryboard(PATIENT_ID, HOSPITAL_ID);

        assertThat(dto.getAllergies()).hasSize(PatientStoryboardServiceImpl.MAX_ALLERGIES);
        assertThat(dto.getProblems()).hasSize(PatientStoryboardServiceImpl.MAX_PROBLEMS);
    }

    @Test
    void hospitalIdNullFallsBackToUnscopedQueries() {
        when(allergyRepo.findByPatient_Id(PATIENT_ID))
            .thenReturn(List.of(allergy("Aspirin", AllergySeverity.MODERATE, true)));
        when(problemRepo.findByPatient_Id(PATIENT_ID)).thenReturn(List.of());
        when(encounterRepo.findByPatient_Id(PATIENT_ID))
            .thenReturn(List.of(encounter(EncounterStatus.IN_PROGRESS, LocalDateTime.now())));
        when(directiveRepo.findByPatient_Id(PATIENT_ID)).thenReturn(List.of());

        PatientStoryboardDTO dto = service.getStoryboard(PATIENT_ID, null);

        assertThat(dto.getAllergies()).hasSize(1);
        assertThat(dto.getActiveEncounter()).isNotNull();
        assertThat(dto.getHospitalId()).isNull();
        assertThat(dto.getHospitalName()).isNull();
    }

    /* ---- helpers ---------------------------------------------------- */

    private Patient buildPatient(String mrn, String codeStatus) {
        Patient p = Patient.builder()
            .firstName("Aïssata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.now().minusYears(28))
            .gender("F")
            .bloodType("O+")
            .codeStatus(codeStatus)
            .build();
        p.setId(PATIENT_ID);

        PatientHospitalRegistration reg = new PatientHospitalRegistration(p, hospital);
        reg.setMrn(mrn);
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        p.setHospitalRegistrations(regs);
        return p;
    }

    private PatientAllergy allergy(String display, AllergySeverity severity, boolean active) {
        PatientAllergy a = new PatientAllergy();
        a.setId(UUID.randomUUID());
        a.setAllergenDisplay(display);
        a.setSeverity(severity);
        a.setVerificationStatus(AllergyVerificationStatus.CONFIRMED);
        a.setReaction("hives");
        a.setActive(active);
        a.setRecordedDate(LocalDate.now().minusDays(10));
        return a;
    }

    private PatientProblem problem(String display, ProblemStatus status, boolean chronic) {
        PatientProblem p = new PatientProblem();
        p.setId(UUID.randomUUID());
        p.setProblemDisplay(display);
        p.setStatus(status);
        p.setChronic(chronic);
        p.setOnsetDate(LocalDate.now().minusYears(1));
        return p;
    }

    private Encounter encounter(EncounterStatus status, LocalDateTime when) {
        Encounter e = Encounter.builder()
            .encounterType(EncounterType.OUTPATIENT)
            .status(status)
            .encounterDate(when)
            .code("ENC-TEST-" + UUID.randomUUID().toString().substring(0, 6))
            .build();
        e.setId(UUID.randomUUID());

        Department department = new Department();
        department.setName("Internal Medicine");
        e.setDepartment(department);

        Staff staff = Staff.builder().build();
        User user = new User();
        user.setFirstName("Marie");
        user.setLastName("Compaoré");
        staff.setUser(user);
        e.setStaff(staff);

        return e;
    }

    private AdvanceDirective directive(AdvanceDirectiveType type, AdvanceDirectiveStatus status) {
        AdvanceDirective d = AdvanceDirective.builder()
            .directiveType(type)
            .status(status)
            .effectiveDate(LocalDate.now().minusDays(20))
            .description("Family-discussed; reviewed with patient.")
            .build();
        d.setId(UUID.randomUUID());
        return d;
    }
}
