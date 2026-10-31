package com.example.hms.repository;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.model.ImagingOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ImagingOrderRepository extends JpaRepository<ImagingOrder, UUID> {

    /** QA D972: a visit's imaging orders, for the checkout charge (withdrawn ones excluded by the caller's list). */
    List<ImagingOrder> findByEncounterIdAndStatusNotIn(UUID encounterId, java.util.Collection<ImagingOrderStatus> excluded);

    /**
     * QA D623 (D508 did not hold): the order by id WITHOUT the repository tenant
     * filter. The scoped {@code findById} answers only for the acting hospital,
     * so an order the by-patient list showed across hospitals 404'd before the
     * service could apply the list's own readable-hospitals rule. Only the
     * by-id READ uses this, and it then applies that rule itself.
     */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM ImagingOrder o WHERE o.id = :id")
    java.util.Optional<ImagingOrder> findByIdUnscoped(@org.springframework.data.repository.query.Param("id") UUID id);

    List<ImagingOrder> findByPatient_IdOrderByOrderedAtDesc(UUID patientId);

    List<ImagingOrder> findByPatient_IdAndStatusOrderByOrderedAtDesc(UUID patientId, ImagingOrderStatus status);

    List<ImagingOrder> findByHospital_IdOrderByOrderedAtDesc(UUID hospitalId);

    List<ImagingOrder> findByHospital_IdAndStatusInOrderByOrderedAtDesc(UUID hospitalId, List<ImagingOrderStatus> statuses);

    List<ImagingOrder> findByStatusOrderByOrderedAtDesc(ImagingOrderStatus status);

    List<ImagingOrder> findAllByOrderByOrderedAtDesc();

    @Query("SELECT o FROM ImagingOrder o " +
        "WHERE o.patient.id = :patientId " +
        "AND o.modality = :modality " +
        "AND (:bodyRegion IS NULL OR LOWER(o.bodyRegion) = LOWER(:bodyRegion)) " +
        "AND o.status <> com.example.hms.enums.ImagingOrderStatus.CANCELLED " +
        "AND o.orderedAt >= :orderedAfter " +
        "ORDER BY o.orderedAt DESC")
    List<ImagingOrder> findPotentialDuplicates(
        @Param("patientId") UUID patientId,
        @Param("modality") ImagingModality modality,
        @Param("bodyRegion") String bodyRegion,
        @Param("orderedAfter") LocalDateTime orderedAfter
    );

    /**
     * Hospital-scoped, ordered, paged variant for the chart-review
     * aggregator. Filtering and limiting happen at the DB level so we
     * never load the full imaging-order history into memory just to
     * keep the most recent N rows.
     */
    Page<ImagingOrder> findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(
        UUID patientId, UUID hospitalId, Pageable pageable);

    /** Unscoped paged variant used when no hospital scope is supplied. */
    Page<ImagingOrder> findByPatient_IdOrderByOrderedAtDesc(UUID patientId, Pageable pageable);

    /** E8 #48 — imaging orders of this patient here; the resolver excludes terminal statuses. */
    List<ImagingOrder> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /** E9 #59b — imaging orders across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    List<ImagingOrder> findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(UUID patientId, java.util.Collection<UUID> hospitalIds);

    /** E9 #60 — the paged sibling, for the chart review. */
    Page<ImagingOrder> findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

    /** E9 #59b — the status-filtered sibling of the finder above. */
    List<ImagingOrder> findByPatient_IdAndHospital_IdInAndStatusOrderByOrderedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, ImagingOrderStatus status);
}
