package com.example.hms.service.recordaccess;

import java.util.Set;
import java.util.UUID;

/**
 * The permission that E8 #49, #50 and #53 consume: may this actor, acting at
 * this hospital, read the patient's chart from <em>other</em> hospitals?
 *
 * <p>Order of the gates, each named by {@code RecordAccessDenialReason}:
 * <ol>
 *   <li>the acting hospital exists;</li>
 *   <li>it is not {@code SCHEMA}-isolated (V97) — cannot be read across, ever;</li>
 *   <li>its posture is {@code TREATMENT_PRESUMED} (E8 #52);</li>
 *   <li>the patient has not opted out (E8 #52);</li>
 *   <li>the actor holds an active staff record at that hospital;</li>
 *   <li>a live treatment relationship exists (E8 #48).</li>
 * </ol>
 *
 * <p>Deliberately says nothing about the actor's <em>role</em> and nothing
 * about super-admins: role security stays on the endpoint where it already
 * is, and whatever a super-admin may read is that endpoint's call. This is
 * the treatment-relationship gate only.
 *
 * <p>Cached once per HTTP request per (actor, patient, hospital). The read
 * filter in #49 asks this question from many repositories in one request;
 * without the cache that would be several carrier queries per row-set.
 */
public interface RecordAccessPolicy {

    RecordAccessDecision decide(UUID actorUserId, UUID patientId, UUID actingHospitalId);

    /**
     * E8 #49 — every hospital whose rows this actor may read for this patient,
     * right now. The acting hospital is always in the set; the others are
     * added only when {@link #decide} permits and the flag is on.
     *
     * <p>The posture is checked at <b>both</b> ends, and they mean different
     * things. On the acting hospital it asks "does this hospital operate the
     * treatment-presumed model at all?". On each source hospital it asks "does
     * this hospital permit its records to be disclosed on that presumption?" —
     * a hospital set to {@code EXPLICIT_CONSENT} keeps its own records behind
     * consent even when the reader's hospital presumes treatment. A
     * {@code SCHEMA}-isolated hospital is excluded from both roles by
     * construction.
     */
    Set<UUID> readableHospitalIds(UUID actorUserId, UUID patientId, UUID actingHospitalId);
}
