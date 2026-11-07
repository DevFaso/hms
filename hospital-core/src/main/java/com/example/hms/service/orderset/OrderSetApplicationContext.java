package com.example.hms.service.orderset;

import java.util.UUID;

/**
 * Immutable bundle of values every {@link OrderSetItemDispatcher#dispatch}
 * call needs. Built once in {@code AdmissionOrderSetServiceImpl#applyToAdmission}
 * from the admission + request and reused for each item in the set.
 *
 * <p>{@code orderingAssignmentId} is the ordering staff's active hospital
 * assignment, resolved once by the service. Required by the LAB fan-out
 * ({@code LabOrderRequestDTO.assignmentId} is {@code @NotNull}); may be
 * null when the staff has no assignment at this hospital — the LAB
 * dispatch returns a skipped result in that case rather than NPE-ing.
 */
public record OrderSetApplicationContext(
    UUID orderSetId,
    String orderSetName,
    String orderSetDescription,
    UUID admissionId,
    UUID patientId,
    UUID hospitalId,
    UUID encounterId,
    UUID orderingStaffId,
    UUID orderingAssignmentId,
    String primaryDiagnosisCode,
    Boolean forceOverride,
    /* Round 18 (QA D935): the reason handed to each medication's override. */
    String overrideReason
) {
    /** The context before round 18: no override reason. */
    public OrderSetApplicationContext(UUID orderSetId, String orderSetName, String orderSetDescription,
                                      UUID admissionId, UUID patientId, UUID hospitalId, UUID encounterId,
                                      UUID orderingStaffId, UUID orderingAssignmentId,
                                      String primaryDiagnosisCode, Boolean forceOverride) {
        this(orderSetId, orderSetName, orderSetDescription, admissionId, patientId, hospitalId, encounterId,
            orderingStaffId, orderingAssignmentId, primaryDiagnosisCode, forceOverride, null);
    }
}
