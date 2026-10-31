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
    /** QA D645/D648: the label shown for an order whose test definition was deleted. */
    public static final String DELETED_TEST_LABEL = "lab.definition.deletedLabel";

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
        Hospital performingHospital = JpaProxyUtils.safeInit(
            labOrder.getPerformingHospital(), OWNER, labOrderId, "performingHospital");
        LabTestDefinition labTestDefinition = JpaProxyUtils.safeInit(
            labOrder.getLabTestDefinition(), OWNER, labOrderId, "labTestDefinition");

        String patientFullName = patient != null ? patient.getFullName() : null;
        String hospitalName = hospital != null ? hospital.getName() : null;
        // QA D645/D648: a definition deleted before D601 is not a blank cell.
        boolean definitionMissing = labTestDefinition == null;
        String labTestName = definitionMissing
            ? com.example.hms.utility.MessageUtil.resolveOrRaw(DELETED_TEST_LABEL)
            : labTestDefinition.getName();
        String labOrderCode = orderCodeOf(labOrder);
        String status = labOrder.getStatus() != null ? labOrder.getStatus().name() : null;

    return LabOrderResponseDTO.builder()
        .id(labOrderId != null ? labOrderId.toString() : null)
        .labOrderCode(labOrderCode)
        .patientId(patient != null && patient.getId() != null
            ? patient.getId().toString() : null)
        .patientFullName(patientFullName)
        // QA D527: the lab worklist has no use for the patient's e-mail; it is not sent.
        .patientEmail(null)
        .hospitalId(hospital != null && hospital.getId() != null ? hospital.getId().toString() : null)
        .hospitalName(hospitalName)
        .performingHospitalId(performingHospital != null && performingHospital.getId() != null
            ? performingHospital.getId().toString() : null)
        .performingHospitalName(performingHospital != null ? performingHospital.getName() : null)
        .labTestName(labTestName)
        .labTestDefinitionMissing(definitionMissing)
            .labTestCode(labTestDefinition != null ? labTestDefinition.getTestCode() : null)
        .orderDatetime(labOrder.getOrderDatetime())
        .status(status)
            .priority(labOrder.getPriority())
            .clinicalIndication(labOrder.getClinicalIndication())
            .medicalNecessityNote(labOrder.getMedicalNecessityNote())
        .notes(labOrder.getNotes())
        .cancellationReason(labOrder.getCancellationReason())
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

    /**
     * QA D291: the code a person reads for an order - its number (LAB-000123)
     * when the database assigned one, else its id. Null for no order.
     */
    public static String orderCodeOf(LabOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getOrderNumber() != null) {
            return order.getOrderNumber();
        }
        return order.getId() != null ? order.getId().toString() : null;
    }
}
