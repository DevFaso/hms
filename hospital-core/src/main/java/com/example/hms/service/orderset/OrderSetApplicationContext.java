package com.example.hms.service.orderset;

import java.util.UUID;

/**
 * Immutable bundle of values every {@link OrderSetItemDispatcher#dispatch}
 * call needs. Built once in {@code AdmissionOrderSetServiceImpl#applyToAdmission}
 * from the admission + request and reused for each item in the set.
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
    String primaryDiagnosisCode,
    Boolean forceOverride
) {}
