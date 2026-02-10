package com.example.hms.mapper;

import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.*;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LabOrderMapper {

    public LabOrderMapper() {
        // No dependencies needed for human-readable mapping
    }

    public LabOrderResponseDTO toLabOrderResponseDTO(LabOrder labOrder) {
    if (labOrder == null) return null;
    String patientFullName = labOrder.getPatient().getFirstName() + " " + labOrder.getPatient().getLastName();
    String patientEmail = labOrder.getPatient().getEmail();
    String hospitalName = labOrder.getHospital() != null ? labOrder.getHospital().getName() : null;
    String labTestName = labOrder.getLabTestDefinition() != null ? labOrder.getLabTestDefinition().getName() : null;
        String labOrderCode = labOrder.getId() != null ? labOrder.getId().toString() : null;
    String status = labOrder.getStatus() != null ? labOrder.getStatus().name() : null;

    return LabOrderResponseDTO.builder()
        .id(labOrder.getId() != null ? labOrder.getId().toString() : null)
        .labOrderCode(labOrderCode)
        .patientFullName(patientFullName)
        .patientEmail(patientEmail)
        .hospitalName(hospitalName)
        .labTestName(labTestName)
            .labTestCode(labOrder.getLabTestDefinition() != null ? labOrder.getLabTestDefinition().getTestCode() : null)
        .orderDatetime(labOrder.getOrderDatetime())
        .status(status)
            .clinicalIndication(labOrder.getClinicalIndication())
            .medicalNecessityNote(labOrder.getMedicalNecessityNote())
        .notes(labOrder.getNotes())
        .primaryDiagnosisCode(labOrder.getPrimaryDiagnosisCode())
        .additionalDiagnosisCodes(labOrder.getAdditionalDiagnosisCodes() == null
            ? List.of()
            : List.copyOf(labOrder.getAdditionalDiagnosisCodes()))
        .orderChannel(labOrder.getOrderChannel() != null ? labOrder.getOrderChannel().name() : null)
        .orderChannelOther(labOrder.getOrderChannelOther())
        .documentationSharedWithLab(labOrder.isDocumentationSharedWithLab())
        .documentationReference(labOrder.getDocumentationReference())
        .orderingProviderNpi(labOrder.getOrderingProviderNpi())
        .providerSignatureDigest(labOrder.getProviderSignatureDigest())
        .signedAt(labOrder.getSignedAt())
        .signedByUserId(labOrder.getSignedByUserId() != null ? labOrder.getSignedByUserId().toString() : null)
        .standingOrder(labOrder.isStandingOrder())
        .standingOrderExpiresAt(labOrder.getStandingOrderExpiresAt())
        .standingOrderLastReviewedAt(labOrder.getStandingOrderLastReviewedAt())
        .standingOrderReviewDueAt(labOrder.getStandingOrderReviewDueAt())
        .standingOrderReviewIntervalDays(labOrder.getStandingOrderReviewIntervalDays())
        .standingOrderReviewNotes(labOrder.getStandingOrderReviewNotes())
        .createdAt(labOrder.getCreatedAt())
        .updatedAt(labOrder.getUpdatedAt())
        .build();
    }


    public LabOrder toLabOrder(
            LabOrderRequestDTO dto,
            Patient patient,
            Staff staff,
            Encounter encounter,
            LabTestDefinition labTestDefinition,
            UserRoleHospitalAssignment assignment,
            Hospital hospital) {

        if (dto == null) return null;

        return LabOrder.builder()
                .patient(patient)
                .orderingStaff(staff)
                .encounter(encounter)
                .labTestDefinition(labTestDefinition)
                .orderDatetime(dto.getOrderDatetime())
                .status(LabOrderStatus.valueOf(dto.getStatus().toUpperCase()))
                .notes(dto.getNotes())
            .clinicalIndication(dto.getClinicalIndication())
            .medicalNecessityNote(dto.getMedicalNecessityNote())
                .primaryDiagnosisCode(dto.getPrimaryDiagnosisCode())
                .additionalDiagnosisCodes(dto.getAdditionalDiagnosisCodes())
                .orderChannel(dto.getOrderChannel() != null ? LabOrderChannel.fromCode(dto.getOrderChannel()) : LabOrderChannel.ELECTRONIC)
                .orderChannelOther(dto.getOrderChannelOther())
                .documentationSharedWithLab(Boolean.TRUE.equals(dto.getDocumentationSharedWithLab()))
                .documentationReference(dto.getDocumentationReference())
                .orderingProviderNpi(dto.getOrderingProviderNpi())
                .signedAt(dto.getSignedAt())
                .standingOrder(Boolean.TRUE.equals(dto.getStandingOrder()))
                .standingOrderExpiresAt(dto.getStandingOrderExpiresAt())
                .standingOrderLastReviewedAt(dto.getStandingOrderLastReviewedAt())
                .standingOrderReviewIntervalDays(dto.getStandingOrderReviewIntervalDays())
                .standingOrderReviewNotes(dto.getStandingOrderReviewNotes())
                .assignment(assignment)
                .hospital(hospital)
                .build();
    }
}
