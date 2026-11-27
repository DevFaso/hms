package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
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
    /** Reload the delivery decision inside its own transaction; the sweep's entity may already be stale. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"pharmacy", "pharmacy.hospital", "medicationCatalogItem"})
    @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> findForAlertDelivery(@Param("id") UUID id);
    /** Transfers hold several stock rows: refuse contention immediately rather than waiting with a partial lock set. */
    @Query(value = "SELECT * FROM clinical.inventory_items WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
    Optional<InventoryItem> tryLockById(@Param("id") UUID id);

    Page<InventoryItem> findByPharmacyIdAndActiveTrue(UUID pharmacyId, Pageable pageable);

    /** QA D138: whether this pharmacy keeps stock here at all, so a fill must name a lot. */
    boolean existsByPharmacyIdAndActiveTrue(UUID pharmacyId);

    /**
     * QA D967: every item of a pharmacy with its catalogue entry, deactivated
     * ones included (a controlled drug taken off the list keeps its register).
     */
    @Query("SELECT i FROM InventoryItem i LEFT JOIN FETCH i.medicationCatalogItem WHERE i.pharmacy.id = :pharmacyId")
    List<InventoryItem> findAllWithCatalogItemByPharmacyId(@Param("pharmacyId") UUID pharmacyId);

    /**
     * QA D270: the item row, locked for the rest of the transaction, so two
     * manual returns cannot both fit under the same not-yet-returned total.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
    Optional<InventoryItem> lockById(@Param("id") UUID id);

    Optional<InventoryItem> findByPharmacyIdAndMedicationCatalogItemId(UUID pharmacyId, UUID medicationCatalogItemId);

    /** QA D4183: the active, lot-tracked items of a pharmacy whose total differs from the sum of their lots. */
    @Query("SELECT i FROM InventoryItem i LEFT JOIN FETCH i.medicationCatalogItem m WHERE i.pharmacy.id = :pharmacyId "
         + "AND i.active = true AND EXISTS (SELECT l.id FROM StockLot l WHERE l.inventoryItem = i) "
         + "AND i.quantityOnHand <> (SELECT COALESCE(SUM(l.remainingQuantity), 0) FROM StockLot l WHERE l.inventoryItem = i) "
         + "ORDER BY m.nameFr, i.id")
    List<InventoryItem> findWithLotBalanceDrift(@Param("pharmacyId") UUID pharmacyId);

    @Query("SELECT i FROM InventoryItem i WHERE i.pharmacy.id = :pharmacyId "
         + "AND i.active = true AND i.quantityOnHand <= i.reorderThreshold")
    List<InventoryItem> findBelowReorderThreshold(@Param("pharmacyId") UUID pharmacyId);

    @Query("SELECT i FROM InventoryItem i WHERE i.pharmacy.hospital.id = :hospitalId "
         + "AND i.active = true AND i.quantityOnHand <= i.reorderThreshold")
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"pharmacy", "pharmacy.hospital", "medicationCatalogItem"})
    List<InventoryItem> findBelowReorderThresholdByHospital(@Param("hospitalId") UUID hospitalId);

    Page<InventoryItem> findByPharmacyHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    List<InventoryItem> findByPharmacyHospitalIdAndMedicationCatalogItemIdAndActiveTrue(UUID hospitalId, UUID medicationCatalogItemId);

    /**
     * QA D899: every active item of the hospital's pharmacies with its catalog
     * item loaded, so the stock check can match a free-text order to the
     * shelf by the dispense DRUG rule without one query per item.
     */
    @Query("SELECT i FROM InventoryItem i JOIN FETCH i.medicationCatalogItem "
         + "WHERE i.pharmacy.hospital.id = :hospitalId AND i.active = true")
    List<InventoryItem> findActiveWithCatalogItemByHospitalId(@Param("hospitalId") UUID hospitalId);

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

    /**
     * Sets the quantity on hand outright: a count correction from the item
     * edit endpoint. Last write wins, as a stock count does; it never rides
     * along with an unrelated edit of a stale copy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE InventoryItem i SET i.quantityOnHand = :quantity, i.updatedAt = :now WHERE i.id = :id")
    int setOnHand(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                  @Param("now") LocalDateTime now);

    /** Puts {@code quantity} back on hand. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE InventoryItem i SET i.quantityOnHand = i.quantityOnHand + :quantity, i.updatedAt = :now "
         + "WHERE i.id = :id")
    int incrementOnHand(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                        @Param("now") LocalDateTime now);

    /** QA D2674 (r65): the item's stock counted {@code factor} times finer (packs to units), atomically. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE InventoryItem i SET i.quantityOnHand = i.quantityOnHand * :factor, i.updatedAt = :now "
         + "WHERE i.id = :id")
    int scaleOnHand(@Param("id") UUID id, @Param("factor") BigDecimal factor, @Param("now") LocalDateTime now);
    /** Platform stock administration includes pharmacy providers as well as hospital stores. */
    @Query("SELECT i.id FROM InventoryItem i WHERE i.active = true AND i.pharmacy.active = true "
        + "AND i.pharmacy.hospital.active = true "
        + "AND i.pharmacy.hospital.lifecycleState = com.example.hms.enums.HospitalLifecycleState.ACTIVE "
        + "AND i.quantityOnHand <= i.reorderThreshold")
    List<UUID> findLowStockIdsAcrossActiveFacilities();

}
