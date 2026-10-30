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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;

/**
 * Implementation of DischargeSummaryService
 * Part of Story #14: Discharge Summary Assembly
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DischargeSummaryServiceImpl implements DischargeSummaryService {
    private static final String DISCHARGE_SUMMARY_NOT_FOUND_KEY = "dischargeSummary.notFound";



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
    private final RecordAccessPolicy recordAccessPolicy;
    private final com.example.hms.repository.EncounterNoteRepository encounterNoteRepository;
    private final CrossHospitalReachRecorder reachRecorder;

    /**
     * Optional Micrometer registry. Auto-configured by spring-boot-starter-actuator in
     * production but may be {@code null} in unit tests that construct this service via
     * Mockito's {@code @InjectMocks} without a corresponding {@code @Mock}. Always go
     * through {@link #incrementCounter} which is null-safe.
     */
    private final MeterRegistry meterRegistry;
    private final org.springframework.context.MessageSource messageSource;
    private final com.example.hms.service.i18n.PatientLocaleResolver patientLocaleResolver;

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
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Encounter encounter = encounterRepository.findById(request.getEncounterId())
            .orElseThrow(() -> new ResourceNotFoundException("encounter.notfound", request.getEncounterId()));

        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", request.getHospitalId()));
        // QA r5 tenancy sweep: the summary is written at the caller's hospital, on that
        // hospital's encounter of this patient; every id in the body is a claim.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, "hospital.notFound", request.getHospitalId());
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(encounter.getHospital(), "encounter.notfound", request.getEncounterId());
        if (encounter.getPatient() == null || !encounter.getPatient().getId().equals(patient.getId())) {
            throw new ResourceNotFoundException("encounter.notfound", request.getEncounterId());
        }

        // QA D1070: a summary is written for a visit someone has seen. A visit
        // still waiting for its physician (or one that ended unseen) has no
        // course, diagnosis or disposition to summarise yet.
        if (encounter.getStatus() != null && NOT_SEEN.contains(encounter.getStatus())) {
            throw new BusinessException("dischargeSummary.encounter.notSeen");
        }

        Staff dischargingProvider = staffRepository.findById(request.getDischargingProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", request.getDischargingProviderId()));
        requireTreatingClinician(encounter, dischargingProvider);
        // QA D503 class: the discharging provider is the one who will sign. A
        // doctor names themself; only a verified super-admin may file it for a
        // provider of the same hospital.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(
            dischargingProvider.getHospital(), "staff.notFound", request.getDischargingProviderId());
        com.example.hms.security.CallerIdentity.requireCallerUnlessSuperAdmin(dischargingProvider);

        UserRoleHospitalAssignment assignment = assignmentRepository.findById(request.getAssignmentId())
            .orElseThrow(() -> new ResourceNotFoundException("roleAssignment.notFound", request.getAssignmentId()));

        DischargeApproval approvalRecord = null;
        if (request.getApprovalRecordId() != null) {
            approvalRecord = dischargeApprovalRepository.findById(request.getApprovalRecordId())
                .orElseThrow(() -> new ResourceNotFoundException("dischargeApproval.notFound", request.getApprovalRecordId()));
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

        return toDto(saved);
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
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_NOT_FOUND_KEY, summaryId));

        enforceHospitalScope(existing, summaryId);
        requireAuthor(existing);

        // Cannot update finalized summaries
        if (Boolean.TRUE.equals(existing.getIsFinalized())) {
            throw new BusinessException("Cannot update a finalized discharge summary");
        }
        takeOverFromNonPhysician(existing);

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

        return toDto(updated);
    }

    @Override
    @Transactional
    public DischargeSummaryResponseDTO finalizeDischargeSummary(UUID summaryId, String providerSignature, UUID providerId, Locale locale) {
        log.info("Finalizing discharge summary: {}", summaryId);

        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_NOT_FOUND_KEY, summaryId));

        enforceHospitalScope(dischargeSummary, summaryId);

        // QA D503: the signer is the signed-in user, never the providerId on the
        // request. Only the discharging provider finalizes, in their own name;
        // a providerId naming anyone else is refused rather than ignored.
        com.example.hms.security.CallerIdentity.requireCaller(dischargeSummary.getDischargingProvider());
        if (providerId != null && !dischargeSummary.getDischargingProvider().getId().equals(providerId)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("identity.notCaller"));
        }

        // Check if already finalized
        if (Boolean.TRUE.equals(dischargeSummary.getIsFinalized())) {
            throw new BusinessException("Discharge summary is already finalized");
        }

        // Finalize
        dischargeSummary.finalizeSummary(providerSignature);

        DischargeSummary finalized = dischargeSummaryRepository.save(dischargeSummary);
        log.info("Discharge summary finalized successfully: {}", summaryId);

        return toDto(finalized);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeSummaryResponseDTO getDischargeSummaryById(UUID summaryId, Locale locale) {
        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_NOT_FOUND_KEY, summaryId));

        enforceHospitalScope(dischargeSummary, summaryId);

        return toDto(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeSummaryResponseDTO getDischargeSummaryByEncounter(UUID encounterId, Locale locale) {
        DischargeSummary dischargeSummary = dischargeSummaryRepository.findByEncounter_Id(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException("dischargeSummary.notFoundForEncounter", encounterId));

        enforceHospitalScope(dischargeSummary, dischargeSummary.getId());

        return toDto(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByPatient(UUID patientId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<DischargeSummary> summaries;
        if (activeHospitalId != null) {
            // E9 #59e — discharge summaries follow the patient across the
            // readable hospitals (untagged, V158); every foreign row is accounted.
            UUID requesterUserId = roleValidator.getCurrentUserId();
            Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, activeHospitalId);
            summaries = dischargeSummaryRepository.findByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(patientId, readable);
            reachRecorder.recordReach(patientId, activeHospitalId, requesterUserId, null,
                CrossHospitalReachRecorder.reachOf(summaries.stream().map(s -> CrossHospitalReachRecorder.hospitalIdOf(s.getHospital())).toList(), activeHospitalId),
                "Cross-hospital discharge summary read on the treatment relationship");
        } else {
            summaries = dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId);
        }
        return summaries.stream()
            .map(this::toDto)
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
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getUnfinalizedDischargeSummaries(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<DischargeSummary> summaries = dischargeSummaryRepository.findUnfinalizedByHospital(effectiveHospitalId);
        return summaries.stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesWithPendingResults(UUID hospitalId, Locale locale) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<DischargeSummary> summaries = dischargeSummaryRepository.findWithPendingTestResults(effectiveHospitalId);
        return summaries.stream()
            .map(this::toDto)
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
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional
    public void deleteDischargeSummary(UUID summaryId, UUID deletedByProviderId) {
        log.info("Deleting discharge summary: {}", summaryId);

        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException(DISCHARGE_SUMMARY_NOT_FOUND_KEY, summaryId));

        enforceHospitalScope(dischargeSummary, summaryId);
        requireAuthor(dischargeSummary);

        // Cannot delete finalized summaries
        if (Boolean.TRUE.equals(dischargeSummary.getIsFinalized())) {
            throw new BusinessException("Cannot delete a finalized discharge summary");
        }

        dischargeSummaryRepository.delete(dischargeSummary);
        log.info("Discharge summary deleted successfully: {}", summaryId);
    }

    /**
     * QA D930: a draft summary belongs to its discharging provider. Another
     * doctor at the same hospital may read it but not rewrite or delete it;
     * only the author (or a verified super-admin in global view) may. Same
     * rule as finalize (QA D503), applied to the other two writes.
     */
    private void requireAuthor(DischargeSummary summary) {
        // Review of #971: the draft is its discharging provider's (checkout
        // names the doctor who closed the visit). Only a summary held by no one,
        // or by a non-physician (the nurse or midwife who opened the visit),
        // falls back to a physician who wrote a note on the visit; a draft
        // held by another doctor never opens to a colleague.
        UUID hospitalId = summary.getHospital() != null ? summary.getHospital().getId() : null;
        if (com.example.hms.security.CallerIdentity.isCaller(summary.getDischargingProvider())
                || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()
                || (!isPhysicianProvider(summary)
                    && isPhysician(roleValidator.getCurrentUserId(), hospitalId)
                    && TreatingClinician.isCaller(summary.getEncounter(), encounterNoteRepository))) {
            return;
        }
        throw new com.example.hms.exception.ClientSafeAccessDeniedException(
            com.example.hms.utility.MessageUtil.resolveOrRaw("identity.notCaller"));
    }

    /**
     * Whether the summary belongs to a physician. A draft held by nobody, or
     * by the nurse or midwife who opened the visit (QA D930 review), is the
     * treating doctor's to finish; one held by a doctor is that doctor's alone.
     */
    private boolean isPhysicianProvider(DischargeSummary summary) {
        Staff provider = summary.getDischargingProvider();
        if (provider == null || provider.getUser() == null) {
            return false;
        }
        return isPhysician(provider.getUser().getId(),
            summary.getHospital() != null ? summary.getHospital().getId() : null);
    }

    /**
     * Review of #971 (D930): the treating physician who edits a draft held by
     * no one or by a non-physician (the nurse who opened the visit) becomes its
     * discharging provider, so she can finalize what she wrote; finalize stays
     * the discharging provider's. A super-admin's edit moves nothing.
     */
    private void takeOverFromNonPhysician(DischargeSummary summary) {
        if (com.example.hms.security.CallerIdentity.isCaller(summary.getDischargingProvider())
                || com.example.hms.security.CallerIdentity.isGlobalSuperAdmin()
                || isPhysicianProvider(summary) || summary.getHospital() == null) {
            return;
        }
        UUID callerId = roleValidator.getCurrentUserId();
        UUID hospitalId = summary.getHospital().getId();
        if (!isPhysician(callerId, hospitalId)) {
            return;
        }
        staffRepository.findByUserIdAndHospitalId(callerId, hospitalId).ifPresent(staff -> {
            summary.setDischargingProvider(staff);
            // Her staff record's own assignment, never a nurse one she also holds;
            // with none on the record, the summary keeps its assignment.
            if (staff.getAssignment() != null) {
                summary.setAssignment(staff.getAssignment());
            }
        });
    }

    /** A doctor, physician or surgeon at the hospital: the same three roles {@code EncounterServiceImpl} counts as a doctor. */
    private boolean isPhysician(UUID userId, UUID hospitalId) {
        return userId != null && hospitalId != null && (roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isPhysician(userId, hospitalId) || roleValidator.isSurgeon(userId, hospitalId));
    }

    /**
     * Whether the signed-in user may update or delete the summary (the rule of
     * {@link #requireAuthor}), so the portal shows the buttons the server
     * accepts (review of #971).
     */
    private boolean editableByCaller(DischargeSummary summary) {
        if (Boolean.TRUE.equals(summary.getIsFinalized())) {
            return false;
        }
        try {
            requireAuthor(summary);
            return true;
        } catch (com.example.hms.exception.ClientSafeAccessDeniedException refused) {
            return false;
        }
    }

    private DischargeSummaryResponseDTO toDto(DischargeSummary summary) {
        DischargeSummaryResponseDTO dto = dischargeSummaryMapper.toResponseDTO(summary);
        if (dto != null) {
            dto.setEditableByCaller(editableByCaller(summary));
        }
        return dto;
    }

    private void enforceHospitalScope(DischargeSummary summary, UUID summaryId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && summary.getHospital() != null
                && !activeHospitalId.equals(summary.getHospital().getId())) {
            throw new ResourceNotFoundException(DISCHARGE_SUMMARY_NOT_FOUND_KEY, summaryId);
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
        // QA D151: a summary backfilled for a prescription's anchor
        // consultation before this filter existed is not shown either.
        // QA D1070: a draft written during a visit still waiting or in progress
        // is not the patient's yet; the summary is published when its visit is
        // completed (checkout), or, with no visit, once finalised.
        List<DischargeSummary> rows = dischargeSummaryRepository
                .findByPatient_IdOrderByDischargeDateDesc(patientId).stream()
                .filter(r -> !isPrescriptionAnchor(r.getEncounter()))
                .filter(DischargeSummaryServiceImpl::publishedToPatient)
                .toList();
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
        incrementCounter(name, outcome, 1.0);
    }

    private void incrementCounter(String name, String outcome, double amount) {
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
            // QA D151: the completed consultation a stand-alone prescription
            // creates is bookkeeping, not a visit; it gets no after-visit summary.
            if (isPrescriptionAnchor(enc)) {
                continue;
            }
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
                summary.setFollowUpInstructions(com.example.hms.mapper.CheckOutMapper.followUpWithSignedNote(
                        enc.getFollowUpInstructions(), enc.getLatestSignedEncounterNote()));

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
            Encounter enc = summary.getEncounter();
            // QA D151: a prescription anchor is never shown, so never enriched.
            if (enc == null || isPrescriptionAnchor(enc)) continue;
            boolean changed = false;

            // Enrich hospitalCourse from encounter notes if missing.
            //
            // Sonar S1066 (merge nested ifs) NOT applied here — the merged form
            // produces 4 condition operands on a single line and Sonar's
            // "new code" coverage gate then requires explicit tests for each
            // branch (proven empirically: the merged form failed PR #313's
            // first SonarCloud run with 4 uncovered conditions). The logic
            // here is unchanged from the original two-stage check. Mark S1066
            // as won't-fix in the SonarCloud UI with a link to this comment.
            if (summary.getHospitalCourse() == null || summary.getHospitalCourse().isBlank()) {
                if (enc.getNotes() != null && !enc.getNotes().isBlank()) {
                    summary.setHospitalCourse(enc.getNotes());
                    changed = true;
                }
            }

            // QA D744: a summary written before the checkout path carried the
            // signed note (encounter 81b42d8d in the round-10 re-test) gains
            // the note's instructions and plan; the join adds only what is missing.
            String followUp = com.example.hms.mapper.CheckOutMapper.followUpWithSignedNote(
                    summary.getFollowUpInstructions(), enc.getLatestSignedEncounterNote());
            if (followUp != null && !followUp.equals(summary.getFollowUpInstructions())) {
                summary.setFollowUpInstructions(followUp);
                changed = true;
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
                // QA D152: a DRAFT or unsigned order was never given to the
                // patient, and a withdrawn one is not continued at discharge.
                if (rx.getStatus() == com.example.hms.enums.PrescriptionStatus.DRAFT
                        || rx.getStatus() == com.example.hms.enums.PrescriptionStatus.PENDING_SIGNATURE
                        || (rx.getStatus() != null && rx.getStatus().isWithdrawn())) {
                    continue;
                }
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
            return diagnosisNotSpecified(encounter);
        }
        // Strip JSON array brackets and quotes: ["A","B"] → A; B
        String stripped = raw.replaceAll("[\\[\\]\"]", "").trim();
        if (stripped.isEmpty()) {
            return diagnosisNotSpecified(encounter);
        }
        return stripped.replace(",", ";");
    }

    /**
     * QA D151: the placeholder is read by the patient, so it is written in the
     * patient's language, not as a hard-coded English sentence.
     */
    private String diagnosisNotSpecified(Encounter encounter) {
        java.util.Locale locale = patientLocaleResolver.resolve(encounter.getPatient(),
                com.example.hms.service.i18n.NotificationLocales.PATIENT_FALLBACK);
        return messageSource.getMessage("dischargeSummary.diagnosis.notSpecified", null,
                "Discharge diagnosis not specified at checkout.", locale);
    }

    static boolean isPrescriptionAnchor(Encounter encounter) {
        return encounter != null && Encounter.PRESCRIPTION_ANCHOR_NOTE.equals(encounter.getNotes());
    }

    /** QA D1070: visits nobody has seen yet, or that ended unseen. */
    private static final java.util.Set<com.example.hms.enums.EncounterStatus> NOT_SEEN = java.util.EnumSet.of(
        com.example.hms.enums.EncounterStatus.SCHEDULED, com.example.hms.enums.EncounterStatus.ARRIVED,
        com.example.hms.enums.EncounterStatus.TRIAGE, com.example.hms.enums.EncounterStatus.WAITING_FOR_PHYSICIAN,
        com.example.hms.enums.EncounterStatus.CANCELLED, com.example.hms.enums.EncounterStatus.LEFT_WITHOUT_BEING_SEEN);

    /** Stays and operations are discharged by the ward's physicians, not only the one the encounter names. */
    private static final java.util.Set<com.example.hms.enums.EncounterType> TEAM_DISCHARGED = java.util.EnumSet.of(
        com.example.hms.enums.EncounterType.INPATIENT, com.example.hms.enums.EncounterType.SURGERY,
        // The emergency department hands patients over at shift change: the receiving doctor discharges.
        com.example.hms.enums.EncounterType.EMERGENCY);

    /**
     * QA D1070: an outpatient visit's after-visit summary is signed by the
     * physician the visit is assigned to - the summary and the visit name the
     * same clinician. An inpatient stay or an operation is discharged by the
     * ward's physicians (attending, covering), as in real hospitals.
     */
    private void requireTreatingClinician(Encounter encounter, Staff dischargingProvider) {
        if (encounter.getEncounterType() != null && TEAM_DISCHARGED.contains(encounter.getEncounterType())) {
            return;
        }
        Staff treating = encounter.getStaff();
        if (treating == null || treating.getId() == null || dischargingProvider == null
                || treating.getId().equals(dischargingProvider.getId())
                // One physician may hold two staff records at a hospital (two departments): the same person.
                || (treating.getUser() != null && dischargingProvider.getUser() != null
                    && treating.getUser().getId() != null
                    && treating.getUser().getId().equals(dischargingProvider.getUser().getId()))) {
            return;
        }
        // A visit opened by a nurse (the walk-in desk) names the nurse: the
        // doctor who then sees the patient writes the summary (QA D930). Only a
        // visit assigned to another physician is that physician's to sum up.
        UUID hospitalId = encounter.getHospital() != null ? encounter.getHospital().getId() : null;
        if (treating.getUser() != null && isPhysician(treating.getUser().getId(), hospitalId)) {
            throw new BusinessException("dischargeSummary.encounter.notTreatingClinician");
        }
    }

    /**
     * QA D1070: what the patient portal publishes - a completed visit's summary, or a finalised one with no visit.
     * A completed visit's summary is shown even when not finalised: the checkout backfill writes those rows
     * unfinalised (backfillMissingDischargeSummaries), and they are the after-visit summary patients read.
     */
    static boolean publishedToPatient(DischargeSummary summary) {
        Encounter visit = summary.getEncounter();
        if (visit == null) {
            return Boolean.TRUE.equals(summary.getIsFinalized());
        }
        return visit.getStatus() == com.example.hms.enums.EncounterStatus.COMPLETED;
    }
}
