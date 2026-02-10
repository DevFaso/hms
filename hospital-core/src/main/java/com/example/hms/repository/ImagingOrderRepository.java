package com.example.hms.repository;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.model.ImagingOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ImagingOrderRepository extends JpaRepository<ImagingOrder, UUID> {

    List<ImagingOrder> findByPatient_IdOrderByOrderedAtDesc(UUID patientId);

    List<ImagingOrder> findByPatient_IdAndStatusOrderByOrderedAtDesc(UUID patientId, ImagingOrderStatus status);

    List<ImagingOrder> findByHospital_IdOrderByOrderedAtDesc(UUID hospitalId);

    List<ImagingOrder> findByHospital_IdAndStatusInOrderByOrderedAtDesc(UUID hospitalId, List<ImagingOrderStatus> statuses);

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
}
