package com.example.hms.repository;

import com.example.hms.enums.ClaimWriteOffStatus;
import com.example.hms.model.InsurerClaimWriteOff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Insurer claims plan PR 4 (V458): the write-off requests on bordereau lines. Every read names the hospital.
 */
public interface InsurerClaimWriteOffRepository extends JpaRepository<InsurerClaimWriteOff, UUID> {

    Optional<InsurerClaimWriteOff> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /** The line a write-off is on, read without loading the write-off. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT w.claimLineId FROM InsurerClaimWriteOff w WHERE w.id = :id AND w.hospitalId = :hospitalId")
    Optional<UUID> claimLineIdOf(@org.springframework.data.repository.query.Param("id") UUID id,
                                 @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId);

    Optional<InsurerClaimWriteOff> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    /** The approver's queue: the hospital's write-offs in the given statuses, oldest request first. */
    List<InsurerClaimWriteOff> findByHospitalIdAndStatusInOrderByRequestedAtAscIdAsc(
        UUID hospitalId, Collection<ClaimWriteOffStatus> statuses);

    /** The write-offs of these lines at one hospital, oldest first. */
    List<InsurerClaimWriteOff> findByClaimLineIdInAndHospitalIdOrderByRequestedAtAscIdAsc(
        Collection<UUID> claimLineIds, UUID hospitalId);

    boolean existsByClaimLineIdInAndHospitalId(Collection<UUID> claimLineIds, UUID hospitalId);
}
