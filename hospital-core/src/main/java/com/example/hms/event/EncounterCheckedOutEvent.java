package com.example.hms.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published by {@code EncounterServiceImpl.checkOut} when a visit is checked
 * out (status COMPLETED), so billing can turn the visit into pending charges
 * or a draft invoice without the clinic code knowing anything about billing
 * (QA D972 hand-off, prepared in round 16; the billing consumer is the
 * follow-up).
 *
 * <p>Ids and scalars only, no JPA entities: a consumer listens with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} and re-loads what
 * it prices in its own transaction, so a failed charge can never roll the
 * checkout back. The billable items are the visit itself (type, department,
 * provider) plus the orders written during it that were not withdrawn.
 *
 * @param encounterId        the checked-out visit
 * @param encounterCode      its human code (ENC-…), for the invoice line
 * @param patientId          the patient billed
 * @param hospitalId         the hospital whose price list applies
 * @param departmentId       the department of the consultation, or null
 * @param providerStaffId    the treating clinician, or null
 * @param encounterType      OUTPATIENT, EMERGENCY, … (the consultation tariff)
 * @param checkedOutAt       when the visit was closed
 * @param prescriptionIds    the visit's prescriptions, withdrawn ones excluded
 * @param labOrderIds        the visit's lab orders
 * @param procedureOrderIds  the visit's procedure orders
 */
public record EncounterCheckedOutEvent(
    UUID encounterId,
    String encounterCode,
    UUID patientId,
    UUID hospitalId,
    UUID departmentId,
    UUID providerStaffId,
    String encounterType,
    LocalDateTime checkedOutAt,
    List<UUID> prescriptionIds,
    List<UUID> labOrderIds,
    List<UUID> procedureOrderIds
) {
    public EncounterCheckedOutEvent {
        prescriptionIds = prescriptionIds == null ? List.of() : List.copyOf(prescriptionIds);
        labOrderIds = labOrderIds == null ? List.of() : List.copyOf(labOrderIds);
        procedureOrderIds = procedureOrderIds == null ? List.of() : List.copyOf(procedureOrderIds);
    }
}
