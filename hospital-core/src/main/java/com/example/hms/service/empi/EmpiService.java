package com.example.hms.service.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.payload.dto.empi.EmpiAliasRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityAliasDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityLinkRequestDTO;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeEventResponseDTO;
import com.example.hms.payload.dto.empi.EmpiMergeRequestDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface EmpiService {

    @Transactional
    EmpiIdentityResponseDTO linkIdentity(EmpiIdentityLinkRequestDTO request);

    @Transactional(readOnly = true)
    EmpiIdentityResponseDTO getIdentity(UUID identityId);

    @Transactional(readOnly = true)
    EmpiIdentityResponseDTO getIdentityByEmpiNumber(String empiNumber);

    @Transactional(readOnly = true)
    Optional<EmpiIdentityResponseDTO> findIdentityByPatientId(UUID patientId);

    @Transactional(readOnly = true)
    Optional<EmpiIdentityResponseDTO> findIdentityByAlias(EmpiAliasType aliasType, String aliasValue);

    @Transactional
    EmpiIdentityAliasDTO addAlias(UUID identityId, EmpiAliasRequestDTO request);

    @Transactional
    void removeAlias(UUID identityId, UUID aliasId);

    /**
     * Merge two duplicate patients at the identity-graph level, provisioning
     * master identities for either patient when absent (admin flow, P1 #8).
     */
    @Transactional
    EmpiMergeEventResponseDTO mergePatients(UUID primaryPatientId, UUID secondaryPatientId,
                                            com.example.hms.enums.empi.EmpiMergeType mergeType, String notes);

    /**
     * {@link #mergePatients}, acting at a hospital the CALLER has already
     * established and authorised, instead of one resolved from the request.
     *
     * <p><b>For callers with no request context only — today, exactly one:
     * the inbound HL7 {@code ADT^A40} path.</b> An MLLP worker thread has no
     * authentication and no {@code HospitalContext}, so {@link #mergePatients}
     * cannot resolve a scope there and refuses every merge. That path already
     * knows the hospital (the allowlisted sender's) and has already checked
     * that both patients are registered there, so it hands that hospital over
     * here rather than fabricating a security context on the worker thread.
     *
     * <p>Nothing about the merge itself is relaxed. {@code actingHospitalId} is
     * held to exactly the rules a caller pinned to that hospital is held to:
     * both patients registered there, both master identities stamped with it,
     * never the global view. What this method trusts is the caller's claim to
     * act AT that hospital, which is why a REST controller must never call it
     * with an id taken from a request — that is {@link #mergePatients}'s job,
     * and its scope comes from the verified context.
     *
     * @param actingHospitalId the hospital the merge acts at; required
     * @throws IllegalArgumentException when {@code actingHospitalId} is null
     */
    @Transactional
    EmpiMergeEventResponseDTO mergePatientsAtAuthorisedHospital(UUID actingHospitalId,
                                                                UUID primaryPatientId, UUID secondaryPatientId,
                                                                com.example.hms.enums.empi.EmpiMergeType mergeType,
                                                                String notes);

    @Transactional
    EmpiMergeEventResponseDTO mergeIdentities(UUID primaryIdentityId, EmpiMergeRequestDTO request);
}
