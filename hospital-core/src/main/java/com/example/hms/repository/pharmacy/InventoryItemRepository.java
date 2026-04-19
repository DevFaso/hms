package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
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
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Page<InventoryItem> findByPharmacyIdAndActiveTrue(UUID pharmacyId, Pageable pageable);

    Optional<InventoryItem> findByPharmacyIdAndMedicationCatalogItemId(UUID pharmacyId, UUID medicationCatalogItemId);

    @Query("SELECT i FROM InventoryItem i WHERE i.pharmacy.id = :pharmacyId "
         + "AND i.active = true AND i.quantityOnHand <= i.reorderThreshold")
    List<InventoryItem> findBelowReorderThreshold(@Param("pharmacyId") UUID pharmacyId);

    @Query("SELECT i FROM InventoryItem i WHERE i.pharmacy.hospital.id = :hospitalId "
         + "AND i.active = true AND i.quantityOnHand <= i.reorderThreshold")
    List<InventoryItem> findBelowReorderThresholdByHospital(@Param("hospitalId") UUID hospitalId);

    Page<InventoryItem> findByPharmacyHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);
}
