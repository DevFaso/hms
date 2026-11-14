package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.LabOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.persistence.JpaProxyUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
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

        Staff orderingStaff = JpaProxyUtils.safeInit(labOrder.getOrderingStaff(), OWNER, labOrderId, "orderingStaff");

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
        .patientMrn(patientMrnAt(patient, hospital, labOrderId))
        .patientDateOfBirth(patient != null ? patient.getDateOfBirth() : null)
        .orderingStaffName(staffNameOf(orderingStaff, labOrderId))
        .hospitalId(hospital != null && hospital.getId() != null ? hospital.getId().toString() : null)
        .hospitalName(hospitalName)
        .performingHospitalId(performingHospital != null && performingHospital.getId() != null
            ? performingHospital.getId().toString() : null)
        .performingHospitalName(performingHospital != null ? performingHospital.getName() : null)
        .labTestName(labTestName)
        .labTestDefinitionMissing(definitionMissing)
            .labTestCode(labTestDefinition != null ? labTestDefinition.getTestCode() : null)
            // QA D1095: the unit a result of this test is entered in (prefilled on entry).
            .labTestUnit(labTestDefinition != null ? labTestDefinition.getUnit() : null)
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


    /**
     * The duplicate-order warning's summary of an earlier order (the imaging
     * order's shape). A new order is built only by
     * {@code LabOrderServiceImpl.buildLabOrder}, which checks for duplicates.
     */
    public LabOrderDuplicateMatchDTO toDuplicateMatchDTO(LabOrder order) {
        if (order == null) {
            return null;
        }
        LabTestDefinition definition = JpaProxyUtils.safeInit(
            order.getLabTestDefinition(), OWNER, order.getId(), "labTestDefinition");
        return LabOrderDuplicateMatchDTO.builder()
            .orderId(order.getId())
            .labOrderCode(orderCodeOf(order))
            .labTestName(definition != null ? definition.getName()
                : com.example.hms.utility.MessageUtil.resolveOrRaw(DELETED_TEST_LABEL))
            .labTestCode(definition != null ? definition.getTestCode() : null)
            .priority(order.getPriority())
            .status(order.getStatus() != null ? order.getStatus().name() : null)
            .orderDatetime(order.getOrderDatetime())
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

    /**
     * The patient's MRN at the order's hospital, or null. A registration row
     * whose hospital was hard-deleted throws inside the lazy collection; the
     * row then shows no MRN rather than failing the whole list.
     */
    private static String patientMrnAt(Patient patient, Hospital hospital, java.util.UUID labOrderId) {
        if (patient == null || hospital == null || hospital.getId() == null) {
            return null;
        }
        try {
            return patient.getMrnForHospital(hospital.getId());
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException ex) {
            log.warn("LabOrder({}) patient has a dangling hospital registration; MRN unavailable.", labOrderId);
            return null;
        }
    }

    /** The ordering clinician's name, or null when the staff row or its user is gone. */
    private static String staffNameOf(Staff staff, java.util.UUID labOrderId) {
        if (staff == null) {
            return null;
        }
        try {
            return staff.getFullName();
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException ex) {
            log.warn("LabOrder({}) ordering staff has a dangling user; name unavailable.", labOrderId);
            return null;
        }
    }
}
