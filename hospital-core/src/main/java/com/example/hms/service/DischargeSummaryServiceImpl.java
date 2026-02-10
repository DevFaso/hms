package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.DischargeSummaryMapper;
import com.example.hms.model.*;
import com.example.hms.model.discharge.*;
import com.example.hms.payload.dto.discharge.*;
import com.example.hms.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of DischargeSummaryService
 * Part of Story #14: Discharge Summary Assembly
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DischargeSummaryServiceImpl implements DischargeSummaryService {

    private final DischargeSummaryRepository dischargeSummaryRepository;
    private final DischargeSummaryMapper dischargeSummaryMapper;
    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final DischargeApprovalRepository dischargeApprovalRepository;

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

        // Add medication reconciliation entries
        if (request.getMedicationReconciliation() != null && !request.getMedicationReconciliation().isEmpty()) {
            for (MedicationReconciliationDTO dto : request.getMedicationReconciliation()) {
                dischargeSummary.addMedicationReconciliation(dischargeSummaryMapper.toMedicationReconciliationEntry(dto));
            }
        }

        // Add pending test results
        if (request.getPendingTestResults() != null && !request.getPendingTestResults().isEmpty()) {
            for (PendingTestResultDTO dto : request.getPendingTestResults()) {
                dischargeSummary.addPendingTestResult(dischargeSummaryMapper.toPendingTestResultEntry(dto));
            }
        }

        // Add follow-up appointments
        if (request.getFollowUpAppointments() != null && !request.getFollowUpAppointments().isEmpty()) {
            for (FollowUpAppointmentDTO dto : request.getFollowUpAppointments()) {
                dischargeSummary.addFollowUpAppointment(dischargeSummaryMapper.toFollowUpAppointmentEntry(dto));
            }
        }

        // Add equipment and supplies
        if (request.getEquipmentAndSupplies() != null && !request.getEquipmentAndSupplies().isEmpty()) {
            request.getEquipmentAndSupplies().forEach(dischargeSummary::addEquipment);
        }

        // Save
        DischargeSummary saved = dischargeSummaryRepository.save(dischargeSummary);
        log.info("Discharge summary created successfully with ID: {}", saved.getId());

        return dischargeSummaryMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public DischargeSummaryResponseDTO updateDischargeSummary(UUID summaryId, DischargeSummaryRequestDTO request, Locale locale) {
        log.info("Updating discharge summary: {}", summaryId);

        DischargeSummary existing = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException("DischargeSummary", "id", summaryId.toString()));

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
            .orElseThrow(() -> new ResourceNotFoundException("DischargeSummary", "id", summaryId.toString()));

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
            .orElseThrow(() -> new ResourceNotFoundException("DischargeSummary", "id", summaryId.toString()));

        return dischargeSummaryMapper.toResponseDTO(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public DischargeSummaryResponseDTO getDischargeSummaryByEncounter(UUID encounterId, Locale locale) {
        DischargeSummary dischargeSummary = dischargeSummaryRepository.findByEncounter_Id(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException("DischargeSummary", "encounter", encounterId.toString()));

        return dischargeSummaryMapper.toResponseDTO(dischargeSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByPatient(UUID patientId, Locale locale) {
        List<DischargeSummary> summaries = dischargeSummaryRepository.findByPatient_IdOrderByDischargeDateDesc(patientId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByHospitalAndDateRange(
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate,
        Locale locale
    ) {
        List<DischargeSummary> summaries = dischargeSummaryRepository.findByHospitalAndDateRange(hospitalId, startDate, endDate);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getUnfinalizedDischargeSummaries(UUID hospitalId, Locale locale) {
        List<DischargeSummary> summaries = dischargeSummaryRepository.findUnfinalizedByHospital(hospitalId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesWithPendingResults(UUID hospitalId, Locale locale) {
        List<DischargeSummary> summaries = dischargeSummaryRepository.findWithPendingTestResults(hospitalId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getDischargeSummariesByProvider(UUID providerId, Locale locale) {
        List<DischargeSummary> summaries = dischargeSummaryRepository.findByDischargingProvider_IdOrderByDischargeDateDesc(providerId);
        return summaries.stream()
            .map(dischargeSummaryMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteDischargeSummary(UUID summaryId, UUID deletedByProviderId) {
        log.info("Deleting discharge summary: {}", summaryId);

        DischargeSummary dischargeSummary = dischargeSummaryRepository.findById(summaryId)
            .orElseThrow(() -> new ResourceNotFoundException("DischargeSummary", "id", summaryId.toString()));

        // Cannot delete finalized summaries
        if (Boolean.TRUE.equals(dischargeSummary.getIsFinalized())) {
            throw new BusinessException("Cannot delete a finalized discharge summary");
        }

        dischargeSummaryRepository.delete(dischargeSummary);
        log.info("Discharge summary deleted successfully: {}", summaryId);
    }
}
