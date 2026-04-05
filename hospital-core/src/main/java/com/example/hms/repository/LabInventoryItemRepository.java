package com.example.hms.repository;

import com.example.hms.model.LabInventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabInventoryItemRepository extends JpaRepository<LabInventoryItem, UUID> {

    Page<LabInventoryItem> findByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    Optional<LabInventoryItem> findByIdAndActiveTrue(UUID id);

    boolean existsByHospitalIdAndItemCode(UUID hospitalId, String itemCode);

    @Query("SELECT i FROM LabInventoryItem i WHERE i.hospital.id = :hospitalId "
         + "AND i.active = true AND i.quantity <= i.reorderThreshold")
    List<LabInventoryItem> findLowStockItems(@Param("hospitalId") UUID hospitalId);

    long countByHospitalIdAndActiveTrue(UUID hospitalId);

    @Query("SELECT COUNT(i) FROM LabInventoryItem i WHERE i.hospital.id = :hospitalId "
         + "AND i.active = true AND i.quantity <= i.reorderThreshold")
    long countLowStockItems(@Param("hospitalId") UUID hospitalId);
}
