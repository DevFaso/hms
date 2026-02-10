package com.example.hms.mapper;

import com.example.hms.model.discharge.*;
import com.example.hms.payload.dto.discharge.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for DischargeSummary entities and DTOs
 * Part of Story #14: Discharge Summary Assembly
 */
@Component
public class DischargeSummaryMapper {

    public DischargeSummaryResponseDTO toResponseDTO(DischargeSummary entity) {
        if (entity == null) {
            return null;
        }

        return DischargeSummaryResponseDTO.builder()
            .id(entity.getId())
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .patientName(entity.getPatient() != null ? entity.getPatient().getFullName() : null)
            .patientMrn(resolvePatientMrn(entity.getPatient(), entity.getHospital()))
            .encounterId(entity.getEncounter() != null ? entity.getEncounter().getId() : null)
            .encounterType(entity.getEncounter() != null && entity.getEncounter().getType() != null ? entity.getEncounter().getType().name() : null)
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
            .dischargingProviderId(entity.getDischargingProvider() != null ? entity.getDischargingProvider().getId() : null)
            .dischargingProviderName(entity.getDischargingProvider() != null ? entity.getDischargingProvider().getFullName() : null)
            .assignmentId(entity.getAssignment() != null ? entity.getAssignment().getId() : null)
            .approvalRecordId(entity.getApprovalRecord() != null ? entity.getApprovalRecord().getId() : null)
            .dischargeDate(entity.getDischargeDate())
            .dischargeTime(entity.getDischargeTime())
            .disposition(entity.getDisposition())
            .dischargeDiagnosis(entity.getDischargeDiagnosis())
            .hospitalCourse(entity.getHospitalCourse())
            .dischargeCondition(entity.getDischargeCondition())
            .activityRestrictions(entity.getActivityRestrictions())
            .dietInstructions(entity.getDietInstructions())
            .woundCareInstructions(entity.getWoundCareInstructions())
            .followUpInstructions(entity.getFollowUpInstructions())
            .warningSigns(entity.getWarningSigns())
            .patientEducationProvided(entity.getPatientEducationProvided())
            .medicationReconciliation(toMedicationReconciliationDTOs(entity.getMedicationReconciliation()))
            .pendingTestResults(toPendingTestResultDTOs(entity.getPendingTestResults()))
            .followUpAppointments(toFollowUpAppointmentDTOs(entity.getFollowUpAppointments()))
            .equipmentAndSupplies(entity.getEquipmentAndSupplies())
            .patientOrCaregiverSignature(entity.getPatientOrCaregiverSignature())
            .signatureDateTime(entity.getSignatureDateTime())
            .providerSignature(entity.getProviderSignature())
            .providerSignatureDateTime(entity.getProviderSignatureDateTime())
            .isFinalized(entity.getIsFinalized())
            .finalizedAt(entity.getFinalizedAt())
            .additionalNotes(entity.getAdditionalNotes())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .version(entity.getVersion())
            .build();
    }

    public List<MedicationReconciliationDTO> toMedicationReconciliationDTOs(List<MedicationReconciliationEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return entries.stream()
            .map(this::toMedicationReconciliationDTO)
            .collect(Collectors.toList());
    }

    public MedicationReconciliationDTO toMedicationReconciliationDTO(MedicationReconciliationEntry entry) {
        if (entry == null) {
            return null;
        }
        return MedicationReconciliationDTO.builder()
            .medicationName(entry.getMedicationName())
            .medicationCode(entry.getMedicationCode())
            .dosage(entry.getDosage())
            .route(entry.getRoute())
            .frequency(entry.getFrequency())
            .reconciliationAction(entry.getReconciliationAction())
            .wasOnAdmission(entry.getWasOnAdmission())
            .givenDuringHospitalization(entry.getGivenDuringHospitalization())
            .continueAtDischarge(entry.getContinueAtDischarge())
            .reasonForChange(entry.getReasonForChange())
            .prescriberNotes(entry.getPrescriberNotes())
            .prescriptionId(entry.getPrescriptionId())
            .patientInstructions(entry.getPatientInstructions())
            .build();
    }

    public MedicationReconciliationEntry toMedicationReconciliationEntry(MedicationReconciliationDTO dto) {
        if (dto == null) {
            return null;
        }
        return MedicationReconciliationEntry.builder()
            .medicationName(dto.getMedicationName())
            .medicationCode(dto.getMedicationCode())
            .dosage(dto.getDosage())
            .route(dto.getRoute())
            .frequency(dto.getFrequency())
            .reconciliationAction(dto.getReconciliationAction())
            .wasOnAdmission(dto.getWasOnAdmission())
            .givenDuringHospitalization(dto.getGivenDuringHospitalization())
            .continueAtDischarge(dto.getContinueAtDischarge())
            .reasonForChange(dto.getReasonForChange())
            .prescriberNotes(dto.getPrescriberNotes())
            .prescriptionId(dto.getPrescriptionId())
            .patientInstructions(dto.getPatientInstructions())
            .build();
    }

    public List<PendingTestResultDTO> toPendingTestResultDTOs(List<PendingTestResultEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return entries.stream()
            .map(this::toPendingTestResultDTO)
            .collect(Collectors.toList());
    }

    public PendingTestResultDTO toPendingTestResultDTO(PendingTestResultEntry entry) {
        if (entry == null) {
            return null;
        }
        return PendingTestResultDTO.builder()
            .testType(entry.getTestType())
            .testName(entry.getTestName())
            .testCode(entry.getTestCode())
            .orderDate(entry.getOrderDate())
            .expectedResultDate(entry.getExpectedResultDate())
            .orderingProvider(entry.getOrderingProvider())
            .followUpProvider(entry.getFollowUpProvider())
            .notificationInstructions(entry.getNotificationInstructions())
            .labOrderId(entry.getLabOrderId())
            .imagingOrderId(entry.getImagingOrderId())
            .isCritical(entry.getIsCritical())
            .patientNotifiedOfPending(entry.getPatientNotifiedOfPending())
            .build();
    }

    public PendingTestResultEntry toPendingTestResultEntry(PendingTestResultDTO dto) {
        if (dto == null) {
            return null;
        }
        return PendingTestResultEntry.builder()
            .testType(dto.getTestType())
            .testName(dto.getTestName())
            .testCode(dto.getTestCode())
            .orderDate(dto.getOrderDate())
            .expectedResultDate(dto.getExpectedResultDate())
            .orderingProvider(dto.getOrderingProvider())
            .followUpProvider(dto.getFollowUpProvider())
            .notificationInstructions(dto.getNotificationInstructions())
            .labOrderId(dto.getLabOrderId())
            .imagingOrderId(dto.getImagingOrderId())
            .isCritical(dto.getIsCritical())
            .patientNotifiedOfPending(dto.getPatientNotifiedOfPending())
            .build();
    }

    public List<FollowUpAppointmentDTO> toFollowUpAppointmentDTOs(List<FollowUpAppointmentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return entries.stream()
            .map(this::toFollowUpAppointmentDTO)
            .collect(Collectors.toList());
    }

    public FollowUpAppointmentDTO toFollowUpAppointmentDTO(FollowUpAppointmentEntry entry) {
        if (entry == null) {
            return null;
        }
        return FollowUpAppointmentDTO.builder()
            .appointmentType(entry.getAppointmentType())
            .providerName(entry.getProviderName())
            .specialty(entry.getSpecialty())
            .appointmentDate(entry.getAppointmentDate())
            .appointmentTime(entry.getAppointmentTime())
            .location(entry.getLocation())
            .phoneNumber(entry.getPhoneNumber())
            .purpose(entry.getPurpose())
            .isConfirmed(entry.getIsConfirmed())
            .confirmationNumber(entry.getConfirmationNumber())
            .appointmentId(entry.getAppointmentId())
            .specialInstructions(entry.getSpecialInstructions())
            .build();
    }

    public FollowUpAppointmentEntry toFollowUpAppointmentEntry(FollowUpAppointmentDTO dto) {
        if (dto == null) {
            return null;
        }
        return FollowUpAppointmentEntry.builder()
            .appointmentType(dto.getAppointmentType())
            .providerName(dto.getProviderName())
            .specialty(dto.getSpecialty())
            .appointmentDate(dto.getAppointmentDate())
            .appointmentTime(dto.getAppointmentTime())
            .location(dto.getLocation())
            .phoneNumber(dto.getPhoneNumber())
            .purpose(dto.getPurpose())
            .isConfirmed(dto.getIsConfirmed())
            .confirmationNumber(dto.getConfirmationNumber())
            .appointmentId(dto.getAppointmentId())
            .specialInstructions(dto.getSpecialInstructions())
            .build();
    }

    /**
     * Resolve patient MRN for the given hospital context
     */
    private String resolvePatientMrn(com.example.hms.model.Patient patient, com.example.hms.model.Hospital hospital) {
        if (patient == null) {
            return null;
        }
        if (hospital != null) {
            return patient.getMrnForHospital(hospital.getId());
        }
        // Fallback: use patient ID as MRN
        return patient.getId() != null ? patient.getId().toString() : null;
    }
}
