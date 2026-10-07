package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    List<InventoryItem> findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(UUID hospitalId, UUID medicationCatalogItemId);

    /*
     * Atomic stock movements (#825 security finding 1). Read-modify-write on
     * the entity lost an update when two requests on DIFFERENT orders moved
     * the same lot at once (both read 10, one wrote 12 or 7, not 9). These
     * compute in the database; a decrement only applies when enough is left,
     * and the caller checks the row count. Bulk JPQL skips @PreUpdate, so
     * updatedAt is set here; the caller refreshes the managed copy.
     */

    /** Takes {@code quantity} off the item when at least that much is on hand; 0 rows otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE InventoryItem i SET i.quantityOnHand = i.quantityOnHand - :quantity, i.updatedAt = :now "
         + "WHERE i.id = :id AND i.quantityOnHand >= :quantity")
    int decrementOnHand(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                        @Param("now") LocalDateTime now);

    /** Puts {@code quantity} back on hand. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE InventoryItem i SET i.quantityOnHand = i.quantityOnHand + :quantity, i.updatedAt = :now "
         + "WHERE i.id = :id")
    int incrementOnHand(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                        @Param("now") LocalDateTime now);
}
