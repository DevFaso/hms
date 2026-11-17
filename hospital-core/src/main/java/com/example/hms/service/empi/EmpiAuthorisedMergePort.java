package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The EMPI patient merge for a caller that has ALREADY established and
 * authorised the hospital it acts at, instead of one resolved from the
 * request.
 *
 * <p><b>A separate, narrow type on purpose.</b> Everything else in EMPI takes
 * its scope from the caller's verified request context; this does not, and a
 * REST controller that reached it with a hospital id taken from a request
 * would bypass that scope entirely. Keeping it off {@link EmpiService} means a
 * class has to ask for THIS type by name to reach it, and
 * {@code EmpiAuthorisedMergePortInjectionTest} fails the build when any class
 * other than the inbound HL7 {@code ADT^A40} path does.
 *
 * <p>Why that path needs it: an MLLP worker thread has no authentication and
 * no {@code HospitalContext}, so {@link EmpiService#mergePatients} cannot
 * resolve a scope there and refuses every merge. The A40 path already knows
 * the hospital (the allowlisted sender's) and has already checked that both
 * patients are registered there and that both master identities are stamped
 * with it, so it hands the hospital over here rather than fabricating a
 * security context on the worker thread.
 *
 * <p>Nothing about the merge itself is relaxed. {@code actingHospitalId} is
 * held to exactly the rules a caller PINNED to that hospital is held to: both
 * patients registered there, both master identities stamped with it, never
 * the global view.
 */
public interface EmpiAuthorisedMergePort {

    /**
     * Merge {@code secondaryPatientId} into {@code primaryPatientId}, acting at
     * {@code actingHospitalId}.
     *
     * @param actingHospitalId the hospital the merge acts at; required
     * @throws IllegalArgumentException when {@code actingHospitalId} is null
     */
    @Transactional
    EmpiMergeEventResponseDTO mergePatientsAtAuthorisedHospital(UUID actingHospitalId,
                                                                UUID primaryPatientId, UUID secondaryPatientId,
                                                                EmpiMergeType mergeType, String notes);
}
