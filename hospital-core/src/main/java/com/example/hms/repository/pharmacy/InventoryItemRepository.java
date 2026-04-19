package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Page<InventoryItem> findByPharmacyIdAndActiveTrue(UUID pharmacyId, Pageable pageable);

    Optional<InventoryItem> findByPharmacyIdAndMedicationCatalogItemId(UUID pharmacyId, UUID medicationCatalogItemId);
}
