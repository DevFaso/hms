package com.example.hms.service;

import com.example.hms.enums.DischargeDisposition;
import com.example.hms.enums.MedicationReconciliationAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeSummaryMapper;
import com.example.hms.model.DischargeApproval;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.discharge.MedicationReconciliationEntry;
import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.discharge.FollowUpAppointmentDTO;
import com.example.hms.payload.dto.discharge.MedicationReconciliationDTO;
import com.example.hms.payload.dto.discharge.PendingTestResultDTO;
import com.example.hms.repository.DischargeApprovalRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Implementation of DischargeSummaryService
 * Part of Story #14: Discharge Summary Assembly
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DischargeSummaryServiceImpl implements DischargeSummaryService {
    private static final String DISCHARGE_SUMMARY_TYPE = "DischargeSummary";


    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final DischargeSummaryMapper dischargeSummaryMapper;
    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final DischargeApprovalRepository dischargeApprovalRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final RoleValidator roleValidator;

    /**
     * Optional Micrometer registry. Auto-configured by spring-boot-starter-actuator in
     * production but may be {@code null} in unit tests that construct this service via
     * Mockito's {@code @InjectMocks} without a corresponding {@code @Mock}. Always go
     * through {@link #incrementCounter} which is null-safe.
     */
    private final MeterRegistry meterRegistry;

    // ── Metric names ─────────────────────────────────────────────────────────
    static final String METRIC_AVS_FETCH_TOTAL = "hms.avs.portal.fetch";
    static final String METRIC_AVS_BACKFILL_TOTAL = "hms.avs.portal.backfill";
    static final String METRIC_AVS_ENRICH_TOTAL = "hms.avs.portal.enrich";
    static final String TAG_OUTCOME = "outcome";
    static final String OUTCOME_HIT = "hit";          // patient had ≥1 summary returned
    static final String OUTCOME_EMPTY = "empty";      // patient had no summaries (and no orphans)
    static final String OUTCOME_BACKFILLED = "backfilled"; // GET-path created or enriched a summary

    @Override
    @Transactional
    public DischargeSummaryResponseDTO createDischargeSummary(DischargeSummaryRequestDTO request, Locale locale) {
        log.info("Creating discharge summary for patient: {}, encounter: {}", request.getPatientId(), request.getEncounterId());

        // Check if discharge summary already exists for this encounter
        if (dischargeSummaryRepository.existsByEncounter_Id(request.getEncounterId())) {
            throw new BusinessException("Discharge summary already exists for this encounter");
        }

        // Fetch required entities
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient", "id", request.getPatientId().toString()));

        Encounter encounter = encounterRepository.findById(request.getEncounterId())
            .orElseThrow(() -> new ResourceNotFoundException("Encounter", "id", request.getEncounterId().toString()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", request.getHospitalId().toString()));

        Staff dischargingProvider = staffRepository.findById(request.getDischargingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("Staff", "id", request.getDischargingProviderId().toString()));

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("Assignment", "id", request.getAssignmentId().toString()));

        DischargeApproval approvalRecord = null;
        if (request.getApprovalRecordId() != null) {
            approvalRecord = dischargeApprovalRepository.findById(request.getApprovalRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("DischargeApproval", "id", request.getApprovalRecordId().toString()));
        }

        // Build discharge summary entity
        DischargeSummary dischargeSummary = DischargeSummary.builder()
            .patient(patient)
            .encounter(encounter)
            .hospital(hospital)
            .dischargingProvider(dischargingProvider)
            .assignment(assignment)
            .approvalRecord(approvalRecord)
            .dischargeDate(request.getDischargeDate())
            .dischargeTime(request.getDischargeTime())
            .disposition(request.getDisposition())
            .dischargeDiagnosis(request.getDischargeDiagnosis())
            .hospitalCourse(request.getHospitalCourse())
            .dischargeCondition(request.getDischargeCondition())
            .activityRestrictions(request.getActivityRestrictions())
            .dietInstructions(request.getDietInstructions())
            .woundCareInstructions(request.getWoundCareInstructions())
            .followUpInstructions(request.getFollowUpInstructions())
            .warningSigns(request.getWarningSigns())
            .patientEducationProvided(request.getPatientEducationProvided())
            .patientOrCaregiverSignature(request.getPatientOrCaregiverSignature())
            .signatureDateTime(request.getSignatureDateTime())
            .additionalNotes(request.getAdditionalNotes())
            .isFinalized(false)
            .build();

        addMedications(dischargeSummary, request);
        addPendingTests(dischargeSummary, request);
        addFollowUps(dischargeSummary, request);

        // Add equipment and supplies
        if (request.getEquipmentAndSupplies() != null && !request.getEquipmentAndSupplies().isEmpty()) {
            request.getEquipmentAndSupplies().forEach(dischargeSummary::addEquipment);
        }

        // Save
        DischargeSummary saved = dischargeSummaryRepository.save(dischargeSummary);
        log.info("Discharge summary created successfully with ID: {}", saved.getId());

        return dischargeSummaryMapper.toResponseDTO(saved);
    }

    private void addMedications(DischargeSummary summary, DischargeSummaryRequestDTO request) {
        if (request.getMedicationReconciliation() == null || request.getMedicationReconciliation().isEmpty()) return;
        for (MedicationReconciliationDTO dto : request.getMedicationReconciliation()) {
            summary.addMedicationReconciliation(dischargeSummaryMapper.toMedicationReconciliationEntry(dto));
        }
    }

    private void addPendingTests(DischargeSummary summary, DischargeSummaryRequestDTO request) {
        if (request.getPendingTestResults() == null || request.getPendingTestResults().isEmpty()) return;
        for (PendingTestResultDTO dto : request.getPendingTestResults()) {
            summary.addPendingTestResult(dischargeSummaryMapper.toPendingTestResultEntry(dto));
        }
    }

    private void addFollowUps(DischargeSummary summary, DischargeSummaryRequestDTO request) {
        if (request.getFollowUpAppointments() == null || request.getFollowUpAppointments().isEmpty()) return;
        for (FollowUpAppointmentDTO dto : request.getFollowUpAppointments()) {
            summary.addFollowUpAppointment(dischargeSummaryMapper.toFollowUpAppointmentEntry(dto));
        }
    }

    @Override
    @Transactional
    public DischargeSummaryResponseDTO updateDischargeSummary(UUID summaryId, DischargeSummaryRequestDTO request, Locale locale) {
        log.info("Updating discharge summary: {}", summaryId);

        DischargeSummary existing = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "id", summaryId.toString()));

        enforceHospitalScope(existing, summaryId);

        // Cannot update finalized summaries
        if (Boolean.TRUE.equals(existing.getIsFinalized())) {
            throw new BusinessException("Cannot update a finalized discharge summary");
        }

        // Update fields
        existing.setDischargeDate(request.getDischargeDate());
        existing.setDischargeTime(request.getDischargeTime());
        existing.setDisposition(request.getDisposition());
        existing.setDischargeDiagnosis(request.getDischargeDiagnosis());
        existing.setHospitalCourse(request.getHospitalCourse());
        existing.setDischargeCondition(request.getDischargeCondition());
        existing.setActivityRestrictions(request.getActivityRestrictions());
        existing.setDietInstructions(request.getDietInstructions());
        existing.setWoundCareInstructions(request.getWoundCareInstructions());
        existing.setFollowUpInstructions(request.getFollowUpInstructions());
        existing.setWarningSigns(request.getWarningSigns());
        existing.setPatientEducationProvided(request.getPatientEducationProvided());
        existing.setPatientOrCaregiverSignature(request.getPatientOrCaregiverSignature());
        existing.setSignatureDateTime(request.getSignatureDateTime());
        existing.setAdditionalNotes(request.getAdditionalNotes());

        // Update collections (clear and rebuild)
        existing.getMedicationReconciliation().clear();
        if (request.getMedicationReconciliation() != null) {
            for (MedicationReconciliationDTO dto : request.getMedicationReconciliation()) {
                existing.addMedicationReconciliation(dischargeSummaryMapper.toMedicationReconciliationEntry(dto));
            }
        }

        existing.getPendingTestResults().clear();
        if (request.getPendingTestResults() != null) {
            for (PendingTestResultDTO dto : request.getPendingTestResults()) {
                existing.addPendingTestResult(dischargeSummaryMapper.toPendingTestResultEntry(dto));
            }
        }

        existing.getFollowUpAppointments().clear();
        if (request.getFollowUpAppointments() != null) {
            for (FollowUpAppointmentDTO dto : request.getFollowUpAppointments()) {
                existing.addFollowUpAppointment(dischargeSummaryMapper.toFollowUpAppointmentEntry(dto));
            }
        }

        existing.getEquipmentAndSupplies().clear();
        if (request.getEquipmentAndSupplies() != null) {
            request.getEquipmentAndSupplies().forEach(existing::addEquipment);
        }

        DischargeSummary updated = dischargeSummaryRepository.save(existing);
        log.info("Discharge summary updated successfully: {}", summaryId);

        return dischargeSummaryMapper.toResponseDTO(updated);
    }

    @Override
    @Transactional
    public DischargeSummaryResponseDTO finalizeDischargeSummary(UUID summaryId, String providerSignature, UUID providerId, Locale locale) {
        log.info("Finalizing discharge summary: {}", summaryId);

        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "id", summaryId.toString()));

        enforceHospitalScope(dischargeSummary, summaryId);

        // Verify provider is authorized
        if (!dischargeSummary.getDischargingProvider().getId().equals(providerId)) {
            throw new BusinessException("Only the discharging provider can finalize the summary");
        }

        // Check if already finalized
        if (Boolean.TRUE.equals(dischargeSummary.getIsFinalized())) {
            throw new BusinessException("Discharge summary is already finalized");
        }

        // Finalize
        dischargeSummary.finalizeSummary(providerSignature);

        DischargeSummary finalized = dischargeSummaryRepository.save(dischargeSummary);
        log.info("Discharge summary finalized successfully: {}", summaryId);

        return dischargeSummaryMapper.toResponseDTO(finalized);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeSummaryResponseDTO getDischargeSummaryById(UUID summaryId, Locale locale) {
        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "id", summaryId.toString()));

        enforceHospitalScope(dischargeSummary, summaryId);

        return dischargeSummaryMapper.toResponseDTO(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeSummaryResponseDTO getDischargeSummaryByEncounter(UUID encounterId, Locale locale) {
        DischargeSummary dischargeSummary = dischargeSummaryRepository.findByEncounter_Id(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "encounter", encounterId.toString()));

        enforceHospitalScope(dischargeSummary, dischargeSummary.getId());

        return dischargeSummaryMapper.toResponseDTO(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByPatient(UUID patientId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<DischargeSummary> summaries;
        if (activeHospitalId != null) {
            summaries = dischargeSummaryRepository.findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(patientId, activeHospitalId);
        } else {
            summaries = dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId);
        }
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByHospitalAndDateRange(
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate,
        Locale locale
    ) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<DischargeSummary> summaries = dischargeSummaryRepository.findByHospitalAndDateRange(effectiveHospitalId, startDate, endDate);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getUnfinalizedDischargeSummaries(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<DischargeSummary> summaries = dischargeSummaryRepository.findUnfinalizedByHospital(effectiveHospitalId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesWithPendingResults(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<DischargeSummary> summaries = dischargeSummaryRepository.findWithPendingTestResults(effectiveHospitalId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByProvider(UUID providerId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<DischargeSummary> summaries;
        if (activeHospitalId != null) {
            summaries = dischargeSummaryRepository.findByDischargingProvider_IdAndHospital_IdOrderByDischargeDateDesc(providerId, activeHospitalId);
        } else {
            summaries = dischargeSummaryRepository.findByDischargingProvider_IdOrderByDischargeDateDesc(providerId);
        }
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public void deleteDischargeSummary(UUID summaryId, UUID deletedByProviderId) {
        log.info("Deleting discharge summary: {}", summaryId);

        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "id", summaryId.toString()));

        enforceHospitalScope(dischargeSummary, summaryId);

        // Cannot delete finalized summaries
        if (Boolean.TRUE.equals(dischargeSummary.getIsFinalized())) {
            throw new BusinessException("Cannot delete a finalized discharge summary");
        }

        dischargeSummaryRepository.delete(dischargeSummary);
        log.info("Discharge summary deleted successfully: {}", summaryId);
    }

    private void enforceHospitalScope(DischargeSummary summary, UUID summaryId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && summary.getHospital() != null
                && !activeHospitalId.equals(summary.getHospital().getId())) {
            throw new ResourceNotFoundException(DISCHARGE_SUMMARY_TYPE, "id", summaryId.toString());
        }
    }

    // ── Portal-specific: patient-centric, no hospital context required ──

    @Override
    @Transactional
    public List<DischargeSummaryResponseDTO> getDischargeSummariesForPortalPatient(UUID patientId) {
        // INFO-level structured log so on-call can answer the
        // "why is the AVS empty on mobile?" question from a single grep
        // (see docs/avs-mobile-user-stories.md, US-AVS-009). Patient ID is
        // already considered semi-PHI but is logged elsewhere on this code path
        // (see PatientPortalServiceImpl.cancelMyRefill etc.); no clinical
        // content is logged here.
        long startNanos = System.nanoTime();

        // 1. Backfill: create DischargeSummary rows for any COMPLETED encounters
        //    that are missing them (defensive — ensures checkout data is always surfaced).
        int backfilled = backfillMissingDischargeSummaries(patientId);

        // 2. Enrich existing summaries that were backfilled before medication/notes enrichment
        int enriched = enrichSparseExistingSummaries(patientId);

        // 3. Return all discharge summaries for this patient
        List<DischargeSummary> rows = dischargeSummaryRepository
                .findByPatient_IdOrderByDischargeDateDesc(patientId);
        List<DischargeSummaryResponseDTO> result = rows.stream()
                .map(dischargeSummaryMapper::toResponseDTO)
                .toList();

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        recordPortalFetchOutcome(patientId, rows, backfilled, enriched, durationMs);
        return result;
    }

    /**
     * Emits the structured INFO log + Prometheus counters that make AVS retrieval
     * incidents diagnosable without database access. Outcome tagging:
     * <ul>
     *   <li>{@code hit} — at least one summary returned</li>
     *   <li>{@code empty} — no summaries and no backfill/enrichment performed
     *       (patient genuinely has no completed encounters)</li>
     *   <li>{@code backfilled} — summaries were created or enriched on this call;
     *       still useful even if the resulting list is also non-empty
     *       (a {@code backfilled} count rising in production usually means a
     *       checkout-time write path is failing silently and needs investigation)</li>
     * </ul>
     */
    private void recordPortalFetchOutcome(
            UUID patientId,
            List<DischargeSummary> rows,
            int backfilled,
            int enriched,
            long durationMs) {
        // Limit encounter ID logging to a small number to keep log lines bounded,
        // even for patients with long histories.
        List<UUID> encounterIds = rows.stream()
                .map(s -> s.getEncounter() != null ? s.getEncounter().getId() : null)
                .filter(java.util.Objects::nonNull)
                .limit(10)
                .toList();

        log.info(
                "AVS portal fetch: patientId={} returnedCount={} backfilledCount={} enrichedCount={} firstEncounterIds={} durationMs={}",
                patientId,
                rows.size(),
                backfilled,
                enriched,
                encounterIds,
                durationMs);

        String outcome;
        if (backfilled > 0 || enriched > 0) {
            outcome = OUTCOME_BACKFILLED;
        } else if (rows.isEmpty()) {
            outcome = OUTCOME_EMPTY;
        } else {
            outcome = OUTCOME_HIT;
        }
        incrementCounter(METRIC_AVS_FETCH_TOTAL, outcome);
        if (backfilled > 0) incrementCounter(METRIC_AVS_BACKFILL_TOTAL, OUTCOME_BACKFILLED, backfilled);
        if (enriched > 0) incrementCounter(METRIC_AVS_ENRICH_TOTAL, OUTCOME_BACKFILLED, enriched);
    }

    private void incrementCounter(String name, String outcome) {
        incrementCounter(name, outcome, 1);
    }

    private void incrementCounter(String name, String outcome, long amount) {
        if (meterRegistry == null) return; // unit-test path
        try {
            Counter.builder(name)
                    .tag(TAG_OUTCOME, outcome)
                    .register(meterRegistry)
                    .increment(amount);
        } catch (Exception ex) {
            // Metrics must never fail the request.
            log.debug("Failed to record metric {}: {}", name, ex.getMessage());
        }
    }

    /**
     * Creates DischargeSummary rows for COMPLETED encounters that somehow lack one.
     * This can happen if the encounter was completed via a code path that didn't call
     * {@code upsertDischargeSummaryForCheckout}, or if the original insert failed
     * silently (e.g. a constraint edge case on a prior version of the code).
     *
     * @return the number of summaries successfully created (≥ 0).
     */
    private int backfillMissingDischargeSummaries(UUID patientId) {
        List<Encounter> orphans = encounterRepository.findCompletedWithoutDischargeSummary(patientId);
        if (orphans.isEmpty()) {
            return 0;
        }
        log.info("Backfilling {} missing discharge summaries for patient {}", orphans.size(), patientId);
        int created = 0;
        for (Encounter enc : orphans) {
            try {
                DischargeSummary summary = new DischargeSummary();
                summary.setEncounter(enc);
                summary.setPatient(enc.getPatient());
                summary.setHospital(enc.getHospital());
                summary.setDischargingProvider(enc.getStaff());
                summary.setAssignment(enc.getAssignment());

                LocalDateTime checkout = enc.getCheckoutTimestamp() != null
                        ? enc.getCheckoutTimestamp() : enc.getEncounterDate();
                summary.setDischargeDate(checkout.toLocalDate());
                summary.setDischargeTime(checkout);
                summary.setDisposition(DischargeDisposition.HOME);

                // Build diagnosis text from JSON array stored on encounter
                String diagText = buildDiagnosisTextFromEncounter(enc);
                summary.setDischargeDiagnosis(diagText);
                summary.setFollowUpInstructions(enc.getFollowUpInstructions());

                // Encounter notes → hospitalCourse (visit summary)
                if (enc.getNotes() != null && !enc.getNotes().isBlank()) {
                    summary.setHospitalCourse(enc.getNotes());
                }

                // Pull prescriptions for this encounter → medication reconciliation
                populateMedicationsFromPrescriptions(summary, enc);

                dischargeSummaryRepository.save(summary);
                created++;
                log.info("Backfilled discharge summary for encounter {}", enc.getId());
            } catch (DataIntegrityViolationException dup) {
                // Concurrent backfill won the race: another mobile load (or the
                // checkout-time write path firing in parallel) inserted a
                // DischargeSummary for this encounter between our orphan query
                // and our save. The unique constraint on
                // discharge_summaries.encounter_id (V92) makes that observable
                // here so we don't end up with duplicates. Treat as a no-op —
                // the enrichment pass that follows will pick up the row that
                // landed first. See Copilot review on PR #259.
                log.info(
                    "Backfill skipped for encounter {} — concurrent insert won the race; existing row will be used",
                    enc.getId());
            } catch (Exception ex) {
                log.warn("Failed to backfill discharge summary for encounter {}: {}",
                        enc.getId(), ex.getMessage());
            }
        }
        return created;
    }

    /**
     * Enriches existing discharge summaries that were created before the medication/notes
     * enrichment logic was added. Populates hospitalCourse and medicationReconciliation
     * for summaries that are missing them.
     *
     * @return the number of summaries enriched (i.e. at least one field changed and saved).
     */
    private int enrichSparseExistingSummaries(UUID patientId) {
        List<DischargeSummary> summaries = dischargeSummaryRepository
                .findByPatient_IdOrderByDischargeDateDesc(patientId);
        int enriched = 0;
        for (DischargeSummary summary : summaries) {
            boolean changed = false;
            Encounter enc = summary.getEncounter();
            if (enc == null) continue;

            // Enrich hospitalCourse from encounter notes if missing
            if (summary.getHospitalCourse() == null || summary.getHospitalCourse().isBlank()) {
                if (enc.getNotes() != null && !enc.getNotes().isBlank()) {
                    summary.setHospitalCourse(enc.getNotes());
                    changed = true;
                }
            }

            // Enrich medications from prescriptions if missing
            if (summary.getMedicationReconciliation() == null
                    || summary.getMedicationReconciliation().isEmpty()) {
                populateMedicationsFromPrescriptions(summary, enc);
                if (summary.getMedicationReconciliation() != null
                        && !summary.getMedicationReconciliation().isEmpty()) {
                    changed = true;
                }
            }

            if (changed) {
                dischargeSummaryRepository.save(summary);
                enriched++;
                log.info("Enriched sparse discharge summary {} for encounter {}",
                        summary.getId(), enc.getId());
            }
        }
        return enriched;
    }

    /**
     * Populates the discharge summary's medication reconciliation list from
     * prescriptions linked to the given encounter.
     */
    private void populateMedicationsFromPrescriptions(DischargeSummary summary, Encounter enc) {
        try {
            Pageable page = PageRequest.of(0, 100);
            List<Prescription> prescriptions = prescriptionRepository
                    .findByEncounter_Id(enc.getId(), page)
                    .getContent();
            for (Prescription rx : prescriptions) {
                MedicationReconciliationEntry entry = MedicationReconciliationEntry.builder()
                        .medicationName(rx.getMedicationName())
                        .dosage(rx.getDosage())
                        .route(rx.getRoute())
                        .frequency(rx.getFrequency())
                        .reconciliationAction(MedicationReconciliationAction.CONTINUED)
                        .continueAtDischarge(true)
                        .build();
                summary.addMedicationReconciliation(entry);
            }
        } catch (Exception ex) {
            log.warn("Could not populate medications for encounter {}: {}", enc.getId(), ex.getMessage());
        }
    }

    /**
     * Extracts a human-readable diagnosis string from the JSON array stored on the encounter.
     */
    private String buildDiagnosisTextFromEncounter(Encounter encounter) {
        String raw = encounter.getDischargeDiagnoses();
        if (raw == null || raw.isBlank()) {
            return "Discharge diagnosis not specified at checkout.";
        }
        // Strip JSON array brackets and quotes: ["A","B"] → A; B
        String stripped = raw.replaceAll("[\\[\\]\"]", "").trim();
        if (stripped.isEmpty()) {
            return "Discharge diagnosis not specified at checkout.";
        }
        return stripped.replace(",", ";");
    }
}
