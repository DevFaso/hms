package com.example.hms.service.integration;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EncounterType;
import com.example.hms.hl7.mllp.AdtVisitSyncProperties;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.platform.AdtIntakeProviderConfigRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService.VisitProjectionResult;
import com.example.hms.service.integration.impl.MllpInboundAdtVisitProjectionServiceImpl;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundAdtVisitProjectionServiceImplTest {

    @Mock private AdmissionRepository admissionRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AdtIntakeProviderConfigRepository intakeConfigRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogService auditEventLogService;

    @Spy private AdtVisitSyncProperties properties = new AdtVisitSyncProperties();

    @InjectMocks private MllpInboundAdtVisitProjectionServiceImpl service;

    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        // Flag ON unless a specific test flips it off.
        properties.setEnabled(true);
    }

    private ParsedAdtMessage adt(String trigger, String visit) {
        return new ParsedAdtMessage(
            trigger, "MRN-1", "AUTH",
            "Doe", "Jane", "",
            LocalDate.of(1985, 1, 1), "F",
            "1 Main St", "Ouagadougou", "", "01000", "BF",
            "I", "WARD-A", visit, null, null);
    }

    @Test
    @DisplayName("SKIPPED when projection flag is off — neither repo is queried")
    void skippedWhenFlagOff() {
        properties.setEnabled(false);

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-1"), patient, hospital, "REG", "HOSP1", "MSG-1");

        assertThat(result).isEqualTo(VisitProjectionResult.SKIPPED);
        verify(admissionRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any());
        verify(encounterRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("SKIPPED when PV1-19 visit number is blank")
    void skippedWhenVisitNumberBlank() {
        VisitProjectionResult result = service.projectVisit(
            adt("A04", ""), patient, hospital, "REG", "HOSP1", "MSG-1");
        assertThat(result).isEqualTo(VisitProjectionResult.SKIPPED);
    }

    @Test
    @DisplayName("ADMISSION_RECONCILED — existing admission matched and stamped")
    void admissionMatched() {
        Admission row = new Admission();
        row.setId(UUID.randomUUID());
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                eq("REG"), eq("HOSP1"), eq("V-1"), eq(hospital.getId())))
            .thenReturn(Optional.of(row));

        VisitProjectionResult result = service.projectVisit(
            adt("A08", "V-1"), patient, hospital, "REG", "HOSP1", "MSG-42");

        assertThat(result).isEqualTo(VisitProjectionResult.ADMISSION_RECONCILED);
        ArgumentCaptor<Admission> saved = ArgumentCaptor.forClass(Admission.class);
        verify(admissionRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalMessageControlId()).isEqualTo("MSG-42");
        // Encounter lookup is skipped once admission matches.
        verify(encounterRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("ENCOUNTER_RECONCILED — falls through to encounter when no admission match")
    void encounterMatched() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        Encounter row = new Encounter();
        row.setId(UUID.randomUUID());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                eq("REG"), eq("HOSP1"), eq("V-2"), eq(hospital.getId())))
            .thenReturn(Optional.of(row));

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-2"), patient, hospital, "REG", "HOSP1", "MSG-7");

        assertThat(result).isEqualTo(VisitProjectionResult.ENCOUNTER_RECONCILED);
        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalMessageControlId()).isEqualTo("MSG-7");
    }

    @Test
    @DisplayName("NO_MATCH when neither repo finds a row for the visit number")
    void noMatch() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-X"), patient, hospital, "REG", "HOSP1", "MSG-1");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(admissionRepository, never()).save(any());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank sender or hospital fields → reconciliation key uses null sender (still proceeds)")
    void blankSenderProceedsWithNullScope() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                eq(null), eq(null), eq("V-3"), eq(hospital.getId())))
            .thenReturn(Optional.empty());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                eq(null), eq(null), eq("V-3"), eq(hospital.getId())))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-3"), patient, hospital, " ", "", "MSG-3");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
    }

    // ── Row-24 follow-on: auto-create gates + happy path ─────────────────

    @Test
    @DisplayName("Auto-create skipped when cluster-wide auto-create flag is off — no extra queries")
    void autoCreateSkippedWhenSubFlagOff() {
        // properties.enabled is on (master), but autoCreate.enabled is off (default).
        stubNoMatchOnReconciliation();

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-1"), patient, hospital, "REG", "HOSP1", "MSG-AC-1");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verifyNoInteractions(intakeConfigRepository);
        verifyNoInteractions(registrationRepository);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Auto-create on A04 hits the Encounter branch and only NO_MATCHes when config is absent")
    void a04ReachesEncounterBranchAndShortCircuitsWithoutConfig() {
        // A04 no longer terminates at the trigger check — it falls
        // through to tryAutoCreateEncounter. Without a config row, the
        // Encounter branch still NO_MATCHes (just via the config-absent
        // gate instead of the trigger-mismatch gate).
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();
        // Explicit stub rather than relying on Mockito's default Optional
        // handling. Mockito 2.x+ returns Optional.empty() for Optional
        // return types via RETURNS_DEFAULTS, but a future Mockito
        // strictness change or a Spy-default switch could break that
        // assumption silently — be explicit. Caught on PR A04 round 1
        // Copilot review (High).
        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-AC-2"), patient, hospital, "REG", "HOSP1", "MSG-AC-2");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(intakeConfigRepository).findByHospital_IdAndEnabledTrue(eq(hospital.getId()));
        verify(admissionRepository, never()).save(any());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Auto-create skipped when per-hospital intake config row is absent")
    void autoCreateSkippedWhenIntakeConfigMissing() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();
        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-3"), patient, hospital, "REG", "HOSP1", "MSG-AC-3");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verifyNoInteractions(registrationRepository);
        verify(admissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Auto-create rejected when patient is not actively registered at receiving hospital (cross-tenant gate)")
    void autoCreateRejectedOnCrossTenantGate() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();
        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(intakeConfig()));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(false);

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-4"), patient, hospital, "REG", "HOSP1", "MSG-AC-4");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(admissionRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("Auto-create writes Admission + stamps reconciliation key + emits audit on the A01 happy path")
    void autoCreateHappyPath() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        Staff provider = staff(config.getAdmittingProviderId());
        Department department = department(config.getDepartmentId());

        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(provider));
        when(departmentRepository.findById(eq(config.getDepartmentId())))
            .thenReturn(Optional.of(department));
        when(admissionRepository.save(any(Admission.class)))
            .thenAnswer(invocation -> {
                Admission a = invocation.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-5"), patient, hospital, "REG", "HOSP1", "MSG-AC-5");

        assertThat(result).isEqualTo(VisitProjectionResult.ADMISSION_AUTOCREATED);
        ArgumentCaptor<Admission> saved = ArgumentCaptor.forClass(Admission.class);
        verify(admissionRepository).save(saved.capture());
        Admission row = saved.getValue();
        assertThat(row.getPatient()).isEqualTo(patient);
        assertThat(row.getAdmittingProvider()).isEqualTo(provider);
        assertThat(row.getDepartment()).isEqualTo(department);
        assertThat(row.getAdmissionType()).isEqualTo(config.getDefaultAdmissionType());
        assertThat(row.getAcuityLevel()).isEqualTo(config.getDefaultAcuityLevel());
        assertThat(row.getChiefComplaint()).isEqualTo(config.getDefaultChiefComplaint());
        // ADT^A01 is an admit notification; auto-created Admissions must
        // land ACTIVE, not PENDING (pre-registration placeholder). Caught
        // on PR #358 Copilot review (Medium).
        assertThat(row.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        // Hospital is stamped from the receivingHospital reference, not
        // dereferenced via provider.getHospital() — defence-in-depth
        // against a wrong-tenant provider UUID in the intake config. Caught
        // on PR #358 Copilot review (High).
        assertThat(row.getHospital()).isSameAs(hospital);
        // Reconciliation key — required so the next A08 routes back here.
        assertThat(row.getExternalVisitNumber()).isEqualTo("V-AC-5");
        assertThat(row.getExternalSendingApplication()).isEqualTo("REG");
        assertThat(row.getExternalSendingFacility()).isEqualTo("HOSP1");
        assertThat(row.getExternalMessageControlId()).isEqualTo("MSG-AC-5");

        ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        AuditEventRequestDTO audit = auditCaptor.getValue();
        assertThat(audit.getEventType()).isEqualTo(AuditEventType.ADMISSION_AUTOCREATED);
        assertThat(audit.getEntityType()).isEqualTo("ADMISSION");
        assertThat(audit.getResourceId()).isEqualTo(row.getId().toString());
    }

    @Test
    @DisplayName("Auto-create rejected when configured provider belongs to a different hospital (PR #358 Copilot High)")
    void autoCreateRejectedOnWrongTenantProvider() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        // The intake-config table stores raw UUIDs (no FK to hospital.staff),
        // so a misconfigured row can point at a Staff member from another
        // tenant. The service must catch this BEFORE saving the Admission.
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(staffAtOtherHospital(config.getAdmittingProviderId())));

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-XT-P"), patient, hospital, "REG", "HOSP1", "MSG-AC-XT-P");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(admissionRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    // ── Row-24 A04 follow-on: Encounter auto-create ─────────────────────

    @Test
    @DisplayName("A04 auto-create writes Encounter + emits audit when config has default_assignment_id and all hospital invariants hold")
    void a04HappyPath() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        config.setDefaultAssignmentId(UUID.randomUUID());
        Staff provider = staff(config.getAdmittingProviderId());
        Department department = department(config.getDepartmentId());
        UserRoleHospitalAssignment assignment =
            assignment(config.getDefaultAssignmentId(), hospital);

        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(provider));
        when(departmentRepository.findById(eq(config.getDepartmentId())))
            .thenReturn(Optional.of(department));
        when(assignmentRepository.findById(eq(config.getDefaultAssignmentId())))
            .thenReturn(Optional.of(assignment));
        when(encounterRepository.save(any(Encounter.class)))
            .thenAnswer(invocation -> {
                Encounter e = invocation.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-A04-OK"), patient, hospital, "REG", "HOSP1", "MSG-A04-OK");

        assertThat(result).isEqualTo(VisitProjectionResult.ENCOUNTER_AUTOCREATED);
        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        Encounter row = saved.getValue();
        assertThat(row.getPatient()).isEqualTo(patient);
        assertThat(row.getStaff()).isEqualTo(provider);
        assertThat(row.getAssignment()).isEqualTo(assignment);
        assertThat(row.getDepartment()).isEqualTo(department);
        assertThat(row.getEncounterType()).isEqualTo(config.getDefaultEncounterType());
        // Hospital stamped from receivingHospital, not from staff or assignment.
        assertThat(row.getHospital()).isSameAs(hospital);
        // Reconciliation key — same shape as the Admission path.
        assertThat(row.getExternalVisitNumber()).isEqualTo("V-A04-OK");
        assertThat(row.getExternalSendingApplication()).isEqualTo("REG");
        assertThat(row.getExternalSendingFacility()).isEqualTo("HOSP1");
        assertThat(row.getExternalMessageControlId()).isEqualTo("MSG-A04-OK");

        ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCaptor.capture());
        AuditEventRequestDTO audit = auditCaptor.getValue();
        assertThat(audit.getEventType()).isEqualTo(AuditEventType.ENCOUNTER_AUTOCREATED);
        assertThat(audit.getEntityType()).isEqualTo("ENCOUNTER");
        assertThat(audit.getResourceId()).isEqualTo(row.getId().toString());

        // Admission path was reached (A01 trigger check failed for A04)
        // but never wrote a row.
        verify(admissionRepository, never()).save(any());
    }

    @Test
    @DisplayName("A04 auto-create skipped when intake config has no default_assignment_id (Encounter requires non-null assignment)")
    void a04SkippedWhenAssignmentIdMissing() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        // defaultAssignmentId left null — config is otherwise A01-ready.
        Staff provider = staff(config.getAdmittingProviderId());
        Department department = department(config.getDepartmentId());
        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(provider));
        when(departmentRepository.findById(eq(config.getDepartmentId())))
            .thenReturn(Optional.of(department));

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-A04-NOASS"), patient, hospital, "REG", "HOSP1", "MSG-A04-NOASS");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        // Shared resolveAutoCreateContext runs first (config/registration/
        // staff/dept), THEN the A04-specific default_assignment_id gate
        // fires. Earlier revision short-circuited at the assignment-id
        // gate before the shared resolve; that ordering was lost when
        // the duplicate code path was extracted to satisfy SonarQube's
        // 3% duplication cap on new code. The load-bearing assertions —
        // assignment lookup not performed, no Encounter saved, no audit —
        // still hold.
        verifyNoInteractions(assignmentRepository);
        verify(encounterRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("A04 auto-create rejected when assignment belongs to a different hospital (defence in depth on Encounter#validate)")
    void a04RejectedOnWrongTenantAssignment() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        config.setDefaultAssignmentId(UUID.randomUUID());
        Staff provider = staff(config.getAdmittingProviderId());
        Department department = department(config.getDepartmentId());
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment crossTenantAssignment =
            assignment(config.getDefaultAssignmentId(), otherHospital);

        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(provider));
        when(departmentRepository.findById(eq(config.getDepartmentId())))
            .thenReturn(Optional.of(department));
        when(assignmentRepository.findById(eq(config.getDefaultAssignmentId())))
            .thenReturn(Optional.of(crossTenantAssignment));

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-A04-XT"), patient, hospital, "REG", "HOSP1", "MSG-A04-XT");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(encounterRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("Auto-create rejected when configured department belongs to a different hospital (PR #358 Copilot High)")
    void autoCreateRejectedOnWrongTenantDepartment() {
        properties.getAutoCreate().setEnabled(true);
        stubNoMatchOnReconciliation();

        AdtIntakeProviderConfig config = intakeConfig();
        Staff provider = staff(config.getAdmittingProviderId());
        // Department UUID points at a department belonging to a different
        // tenant — same class of bug as the provider one above.
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        Department crossTenantDept = department(config.getDepartmentId(), otherHospital);

        when(intakeConfigRepository.findByHospital_IdAndEnabledTrue(eq(hospital.getId())))
            .thenReturn(Optional.of(config));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(
            eq(patient.getId()), eq(hospital.getId()))).thenReturn(true);
        when(staffRepository.findById(eq(config.getAdmittingProviderId())))
            .thenReturn(Optional.of(provider));
        when(departmentRepository.findById(eq(config.getDepartmentId())))
            .thenReturn(Optional.of(crossTenantDept));

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-AC-XT-D"), patient, hospital, "REG", "HOSP1", "MSG-AC-XT-D");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(admissionRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void stubNoMatchOnReconciliation() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    private AdtIntakeProviderConfig intakeConfig() {
        AdtIntakeProviderConfig c = new AdtIntakeProviderConfig();
        c.setHospital(hospital);
        c.setAdmittingProviderId(UUID.randomUUID());
        c.setDepartmentId(UUID.randomUUID());
        c.setDefaultAdmissionType(AdmissionType.EMERGENCY);
        c.setDefaultAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        c.setDefaultEncounterType(EncounterType.INPATIENT);
        c.setDefaultChiefComplaint("Auto-created from ADT^A01");
        c.setEnabled(true);
        return c;
    }

    private Staff staff(UUID id) {
        Staff s = new Staff();
        s.setId(id);
        s.setHospital(hospital);
        return s;
    }

    private Department department(UUID id) {
        return department(id, hospital);
    }

    private Department department(UUID id, Hospital h) {
        Department d = new Department();
        d.setId(id);
        d.setHospital(h);
        return d;
    }

    private UserRoleHospitalAssignment assignment(UUID id, Hospital h) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(id);
        a.setHospital(h);
        return a;
    }

    private Staff staffAtOtherHospital(UUID id) {
        Staff s = new Staff();
        s.setId(id);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        s.setHospital(other);
        return s;
    }
}
