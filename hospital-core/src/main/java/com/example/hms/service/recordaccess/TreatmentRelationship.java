package com.example.hms.service.recordaccess;

import com.example.hms.enums.TreatmentRelationshipKind;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A live clinical relationship between a patient and the hospital a clinician
 * is acting in (E8 #48) — the single fact the whole access model rests on.
 *
 * @param kind        the carrier that establishes it, strongest first
 * @param carrierId   the admission / encounter / appointment / panel / order id
 * @param hospitalId  the hospital the relationship is with
 * @param patientId   the patient
 * @param establishedAt when the carrier began
 * @param expiresAt   when the relationship lapses under the decay rule;
 *                    {@code null} means "while the carrier stays open"
 *                    (a standing panel assignment, an admission still in progress)
 * @param actorDirectlyAttached whether the acting clinician is named on the
 *                    carrier (attending, ordering, panel provider) rather than
 *                    merely staff at the hospital — recorded for audit richness,
 *                    it does not change the answer
 */
public record TreatmentRelationship(
    TreatmentRelationshipKind kind,
    UUID carrierId,
    UUID hospitalId,
    UUID patientId,
    LocalDateTime establishedAt,
    LocalDateTime expiresAt,
    boolean actorDirectlyAttached
) {
}
