package com.example.hms.service.recordaccess;

import java.util.Optional;
import java.util.UUID;

/**
 * E8 #48 — the one predicate the treatment-relationship model rests on:
 * <em>does this patient have a live clinical relationship with the hospital
 * this actor is acting in?</em>
 *
 * <p>Deliberately narrow. It answers with a fact, not a permission — the
 * posture switch, the patient opt-out, the tenant-isolation carve-out and the
 * actor's staff standing all live in {@link RecordAccessPolicy}, which is what
 * callers consume. No controller may hand-roll either.
 *
 * <p>The relationship is between the <b>patient and the hospital</b>, not the
 * individual clinician. That is Epic's shape and it is the only one that
 * survives contact with an emergency department: the triage nurse, the
 * attending and the pharmacist are all treating the patient the moment they
 * arrive, and none of them is "the" clinician on the carrier. Role security
 * on the endpoint decides what each of them may then see. Whether the actor
 * is named on the carrier is reported ({@code actorDirectlyAttached}) for the
 * audit trail, and changes nothing.
 */
public interface TreatmentRelationshipResolver {

    /**
     * @param patientId  the patient
     * @param hospitalId the hospital the actor is acting in
     * @param actorUserId the acting user, used only to mark direct attachment
     * @return the strongest live carrier under the decay rule, or empty
     */
    Optional<TreatmentRelationship> resolve(UUID patientId, UUID hospitalId, UUID actorUserId);
}
