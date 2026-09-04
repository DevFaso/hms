package com.example.hms.repository;

import com.example.hms.enums.RoiRequestStatus;
import com.example.hms.model.RoiRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoiRequestRepository extends JpaRepository<RoiRequest, UUID> {

    /** The records-desk worklist: one status, oldest request first. */
    @EntityGraph(attributePaths = {"decidedBy"})
    Page<RoiRequest> findByHospital_IdAndStatusOrderByRequestedOnAscCreatedAtAsc(
            UUID hospitalId, RoiRequestStatus status, Pageable pageable);

    /** One patient's requests at the caller's hospital, newest first — the chart view. */
    @EntityGraph(attributePaths = {"decidedBy"})
    List<RoiRequest> findByPatientIdAndHospital_IdOrderByCreatedAtDesc(
            UUID patientId, UUID hospitalId);

    /** The patient's own requests across hospitals, newest first — the /me view. */
    @EntityGraph(attributePaths = {"hospital", "decidedBy"})
    List<RoiRequest> findByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
