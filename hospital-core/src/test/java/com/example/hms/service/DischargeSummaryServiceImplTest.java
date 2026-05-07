package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeSummaryMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.repository.DischargeApprovalRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DischargeSummaryServiceImplTest {

    @Mock private DischargeSummaryRepository dischargeSummaryRepository;
    @Mock private DischargeSummaryMapper dischargeSummaryMapper;
    @Mock private PatientRepository patientRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private DischargeApprovalRepository dischargeApprovalRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private com.example.hms.utility.RoleValidator roleValidator;
    /** Real registry so the portal-fetch tests can assert counter values. */
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks private DischargeSummaryServiceImpl service;

    private UUID summaryId, patientId, encounterId, hospitalId, staffId, assignmentId;
    private Patient patient;
    private Encounter encounter;
    private Hospital hospital;
    private Staff staff;
    private UserRoleHospitalAssignment assignment;
    private DischargeSummary summary;
    private DischargeSummaryResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        summaryId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();

        patient = new Patient(); patient.setId(patientId);
        encounter = new Encounter(); encounter.setId(encounterId);
        hospital = new Hospital(); hospital.setId(hospitalId);
        staff = new Staff(); staff.setId(staffId);
        assignment = new UserRoleHospitalAssignment(); assignment.setId(assignmentId);

        summary = DischargeSummary.builder()
            .patient(patient).encounter(encounter).hospital(hospital)
            .dischargingProvider(staff).assignment(assignment)
            .isFinalized(false)
            .dischargeDate(LocalDate.now())
            .build();
        summary.setId(summaryId);

        responseDTO = DischargeSummaryResponseDTO.builder().id(summaryId).build();
    }

    private DischargeSummaryRequestDTO buildRequest() {
        DischargeSummaryRequestDTO req = new DischargeSummaryRequestDTO();
        req.setPatientId(patientId);
        req.setEncounterId(encounterId);
        req.setHospitalId(hospitalId);
        req.setDischargingProviderId(staffId);
        req.setAssignmentId(assignmentId);
        req.setDischargeDate(LocalDate.now());
        req.setDischargeDiagnosis("Diagnosis");
        req.setHospitalCourse("Course");
        return req;
    }

    @Test void createDischargeSummary_success() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(false);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(dischargeSummaryRepository.save(any())).thenAnswer(inv -> { DischargeSummary s = inv.getArgument(0); s.setId(summaryId); return s; });
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);

        DischargeSummaryResponseDTO result = service.createDischargeSummary(req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void createDischargeSummary_alreadyExists_throws() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(true);
        assertThatThrownBy(() -> service.createDischargeSummary(req, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void createDischargeSummary_patientNotFound() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.existsByEncounter_Id(encounterId)).thenReturn(false);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createDischargeSummary(req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void updateDischargeSummary_success() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any())).thenReturn(summary);
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);
        DischargeSummaryResponseDTO result = service.updateDischargeSummary(summaryId, req, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void updateDischargeSummary_notFound() {
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void updateDischargeSummary_finalized_throws() {
        summary.setIsFinalized(true);
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void finalizeDischargeSummary_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryRepository.save(any())).thenReturn(summary);
        when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);
        DischargeSummaryResponseDTO result = service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH);
        assertThat(result.getId()).isEqualTo(summaryId);
    }

    @Test void finalizeDischargeSummary_wrongProvider_throws() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        UUID randomId = UUID.randomUUID();
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", randomId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void finalizeDischargeSummary_alreadyFinalized_throws() {
        summary.setIsFinalized(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test void getDischargeSummaryById_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void getDischargeSummaryById_notFound() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDischargeSummaryById(summaryId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryByEncounter_success() {
        when(dischargeSummaryRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryByEncounter(encounterId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void getDischargeSummariesByPatient() {
        when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByPatient(patientId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByHospitalAndDateRange() {
        LocalDate start = LocalDate.now().minusDays(30); LocalDate end = LocalDate.now();
        when(dischargeSummaryRepository.findByHospitalAndDateRange(hospitalId, start, end)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByHospitalAndDateRange(hospitalId, start, end, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getUnfinalizedDischargeSummaries() {
        when(dischargeSummaryRepository.findUnfinalizedByHospital(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getUnfinalizedDischargeSummaries(hospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesWithPendingResults() {
        when(dischargeSummaryRepository.findWithPendingTestResults(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesWithPendingResults(hospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByProvider() {
        when(dischargeSummaryRepository.findByDischargingProvider_IdOrderByDischargeDateDesc(staffId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByProvider(staffId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void deleteDischargeSummary_success() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        service.deleteDischargeSummary(summaryId, staffId);
        verify(dischargeSummaryRepository).delete(summary);
    }

    @Test void deleteDischargeSummary_notFound() {
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void deleteDischargeSummary_finalized_throws() {
        summary.setIsFinalized(true);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(BusinessException.class);
    }

    // ── enforceHospitalScope: cross-hospital → 404 ────────────────────

    @Test void getDischargeSummaryById_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.getDischargeSummaryById(summaryId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryById_sameHospital_success() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummaryById(summaryId, Locale.ENGLISH).getId()).isEqualTo(summaryId);
    }

    @Test void updateDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        DischargeSummaryRequestDTO req = buildRequest();
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.updateDischargeSummary(summaryId, req, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void finalizeDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.finalizeDischargeSummary(summaryId, "Dr. Sig", staffId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getDischargeSummaryByEncounter_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findByEncounter_Id(encounterId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.getDischargeSummaryByEncounter(encounterId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void deleteDischargeSummary_crossHospital_throws() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(otherHospitalId);
        when(dischargeSummaryRepository.findById(summaryId)).thenReturn(Optional.of(summary));
        assertThatThrownBy(() -> service.deleteDischargeSummary(summaryId, staffId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Scoped list queries: activeHospitalId non-null ─────────────────

    @Test void getDischargeSummariesByPatient_scoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, hospitalId))
            .thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByPatient(patientId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesByProvider_scoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findByDischargingProvider_IdAndHospital_IdOrderByDischargeDateDesc(staffId, hospitalId))
            .thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesByProvider(staffId, Locale.ENGLISH)).hasSize(1);
    }

    // ── effectiveHospitalId override: activeHospitalId overrides param ─

    @Test void getDischargeSummariesByHospitalAndDateRange_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findByHospitalAndDateRange(hospitalId, start, end)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        // passes otherHospitalId but activeHospitalId overrides it
        assertThat(service.getDischargeSummariesByHospitalAndDateRange(otherHospitalId, start, end, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getUnfinalizedDischargeSummaries_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findUnfinalizedByHospital(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getUnfinalizedDischargeSummaries(otherHospitalId, Locale.ENGLISH)).hasSize(1);
    }

    @Test void getDischargeSummariesWithPendingResults_scopedOverride() {
        UUID otherHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dischargeSummaryRepository.findWithPendingTestResults(hospitalId)).thenReturn(List.of(summary));
        when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);
        assertThat(service.getDischargeSummariesWithPendingResults(otherHospitalId, Locale.ENGLISH)).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // getDischargeSummariesForPortalPatient — root-cause coverage for the
    // 2026-04-11 incident (encounter COMPLETED yesterday but mobile AVS empty).
    // See docs/avs-mobile-user-stories.md (US-AVS-008, US-AVS-009).
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Portal-patient AVS retrieval — observability + backfill")
    class PortalPatientAvs {

        private double counterCount(String name, String outcome) {
            io.micrometer.core.instrument.Counter c = meterRegistry.find(name)
                    .tag(DischargeSummaryServiceImpl.TAG_OUTCOME, outcome)
                    .counter();
            return c == null ? 0.0d : c.count();
        }

        @Test
        @DisplayName("empty patient → counter tagged outcome=empty, no save calls")
        void portalFetch_empty_taggedEmpty() {
            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of());

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).isEmpty();
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_EMPTY)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isZero();
        }

        @Test
        @DisplayName("existing summary returned → counter tagged outcome=hit")
        void portalFetch_existingSummary_taggedHit() {
            // Prime the existing summary with hospitalCourse + a medication so the
            // enrichment branch does not fire and the outcome resolves to "hit".
            summary.setHospitalCourse("seen");
            summary.addMedicationReconciliation(
                    com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
                        .medicationName("Aspirin").dosage("81mg")
                        .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
                        .continueAtDischarge(true)
                        .build());

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).hasSize(1);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isZero();
        }

        @Test
        @DisplayName("orphan COMPLETED encounter → backfilled, counter tagged backfilled")
        void portalFetch_orphanEncounter_backfilled() {
            // Simulate the 2026-04-11 incident: encounter COMPLETED with notes +
            // discharge_diagnoses + checkout_timestamp but no DischargeSummary row.
            Encounter orphan = new Encounter();
            orphan.setId(UUID.randomUUID());
            orphan.setPatient(patient);
            orphan.setHospital(hospital);
            orphan.setStaff(staff);
            orphan.setAssignment(assignment);
            orphan.setStatus(com.example.hms.enums.EncounterStatus.COMPLETED);
            orphan.setCheckoutTimestamp(java.time.LocalDateTime.now().minusDays(1));
            orphan.setNotes("testing updates");
            orphan.setDischargeDiagnoses("[\"Patient is suffering from testing workflow\"]");
            orphan.setFollowUpInstructions("return to clinic after seeing the Cardiologist in 2 weeks");

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of(orphan));
            when(dischargeSummaryRepository.save(any())).thenAnswer(inv -> {
                DischargeSummary s = inv.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(any())).thenReturn(responseDTO);

            List<DischargeSummaryResponseDTO> result =
                    service.getDischargeSummariesForPortalPatient(patientId);

            assertThat(result).hasSize(1);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isEqualTo(1.0d);
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_BACKFILL_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_BACKFILLED)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("calling twice with no orphans is idempotent — no duplicate writes")
        void portalFetch_idempotent_noDuplicateWrites() {
            summary.setHospitalCourse("already enriched");
            summary.addMedicationReconciliation(
                    com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
                        .medicationName("Aspirin").dosage("81mg")
                        .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
                        .continueAtDischarge(true)
                        .build());

            when(encounterRepository.findCompletedWithoutDischargeSummary(patientId))
                    .thenReturn(List.of());
            when(dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId))
                    .thenReturn(List.of(summary));
            when(dischargeSummaryMapper.toResponseDTO(summary)).thenReturn(responseDTO);

            service.getDischargeSummariesForPortalPatient(patientId);
            service.getDischargeSummariesForPortalPatient(patientId);

            // No saves at all — the existing summary is already enriched.
            verify(dischargeSummaryRepository, org.mockito.Mockito.never()).save(any());
            assertThat(counterCount(
                    DischargeSummaryServiceImpl.METRIC_AVS_FETCH_TOTAL,
                    DischargeSummaryServiceImpl.OUTCOME_HIT)).isEqualTo(2.0d);
        }
    }
}
