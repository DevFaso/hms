package com.example.hms.mapper;

import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.persistence.JpaProxyUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LabOrderMapper {

    /** Owning-entity label used in {@link JpaProxyUtils#safeInit} log lines. */
    private static final String OWNER = "LabOrder";

    public LabOrderMapper() {
        // No dependencies needed for human-readable mapping
    }

    public LabOrderResponseDTO toLabOrderResponseDTO(LabOrder labOrder) {
        if (labOrder == null) return null;

        // Force-initialise each lazy association up front and substitute null
        // when the referenced row was hard-deleted (dangling FK). Without this
        // defence the bare `labOrder.getPatient().getFirstName()` below blows
        // up the whole list response with a 500 the moment a single patient
        // row is missing — the exact failure observed on dev's
        // /super-admin/recent-activity endpoint.
        java.util.UUID labOrderId = labOrder.getId();
        Patient patient = JpaProxyUtils.safeInit(labOrder.getPatient(), OWNER, labOrderId, "patient");
        Hospital hospital = JpaProxyUtils.safeInit(labOrder.getHospital(), OWNER, labOrderId, "hospital");
        LabTestDefinition labTestDefinition = JpaProxyUtils.safeInit(
            labOrder.getLabTestDefinition(), OWNER, labOrderId, "labTestDefinition");

        String patientFullName = patient != null
            ? buildFullName(patient.getFirstName(), patient.getLastName())
            : null;
        String patientEmail = patient != null ? patient.getEmail() : null;
        String hospitalName = hospital != null ? hospital.getName() : null;
        String labTestName = labTestDefinition != null ? labTestDefinition.getName() : null;
        String labOrderCode = labOrderId != null ? labOrderId.toString() : null;
        String status = labOrder.getStatus() != null ? labOrder.getStatus().name() : null;

    return LabOrderResponseDTO.builder()
        .id(labOrderId != null ? labOrderId.toString() : null)
        .labOrderCode(labOrderCode)
        .patientId(patient != null && patient.getId() != null
            ? patient.getId().toString() : null)
        .patientFullName(patientFullName)
        .patientEmail(patientEmail)
        .hospitalName(hospitalName)
        .labTestName(labTestName)
            .labTestCode(labTestDefinition != null ? labTestDefinition.getTestCode() : null)
        .orderDatetime(labOrder.getOrderDatetime())
        .status(status)
            .priority(labOrder.getPriority())
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
                .priority(dto.getPriority() != null && !dto.getPriority().isBlank() ? dto.getPriority().trim().toUpperCase(java.util.Locale.ROOT) : "ROUTINE")
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

    private static String buildFullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
