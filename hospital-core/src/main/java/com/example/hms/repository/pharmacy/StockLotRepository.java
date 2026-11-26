package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.StockLot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface StockLotRepository extends JpaRepository<StockLot, UUID> {
    /** A transfer never waits for a lot while it holds another stock row. */
    @Query(value = "SELECT * FROM clinical.stock_lots WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
    java.util.Optional<StockLot> tryLockById(@Param("id") UUID id);

    Page<StockLot> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    /** QA D2674 (r65): every lot of an item, shortest-dated first. */
    List<StockLot> findAllByInventoryItemIdOrderByExpiryDateAsc(UUID inventoryItemId);

    /** QA D315: whether this lot number was already received for the item with another expiry. */
    @Query("SELECT (COUNT(s) > 0) FROM StockLot s WHERE s.inventoryItem.id = :inventoryItemId "
         + "AND UPPER(s.lotNumber) = UPPER(CAST(:lotNumber AS string)) AND s.expiryDate <> :expiryDate")
    boolean existsOtherExpiryForLot(@Param("inventoryItemId") UUID inventoryItemId,
                                    @Param("lotNumber") String lotNumber,
                                    @Param("expiryDate") LocalDate expiryDate);

    /**
     * QA D733: the lot already received for this item with this number and
     * expiry; a second delivery of the same batch adds to it.
     */
    @Query("SELECT s FROM StockLot s WHERE s.inventoryItem.id = :inventoryItemId "
         + "AND UPPER(s.lotNumber) = UPPER(CAST(:lotNumber AS string)) AND s.expiryDate = :expiryDate")
    List<StockLot> findSameLot(@Param("inventoryItemId") UUID inventoryItemId,
                               @Param("lotNumber") String lotNumber,
                               @Param("expiryDate") LocalDate expiryDate);

    /** QA D733: a further delivery of an existing lot, received and on hand. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.initialQuantity = l.initialQuantity + :quantity, "
         + "l.remainingQuantity = l.remainingQuantity + :quantity, l.updatedAt = :now WHERE l.id = :id")
    int addReceipt(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                   @Param("now") LocalDateTime now);

    /** D82: whether the item is tracked in lots at all. */
    boolean existsByInventoryItemId(UUID inventoryItemId);

    /** Review of #1239 (per-lot balance): every lot of one pharmacy, with its item and medication. */
    @Query("SELECT s FROM StockLot s JOIN FETCH s.inventoryItem i LEFT JOIN FETCH i.medicationCatalogItem "
         + "WHERE i.pharmacy.id = :pharmacyId")
    List<StockLot> findAllWithItemByPharmacyId(@Param("pharmacyId") UUID pharmacyId);

    @Query("SELECT s FROM StockLot s WHERE s.inventoryItem.id = :inventoryItemId " +
           "AND s.remainingQuantity > 0 AND s.expiryDate > CURRENT_DATE " +
           "ORDER BY s.expiryDate ASC")
    List<StockLot> findAvailableLotsByFEFO(@Param("inventoryItemId") UUID inventoryItemId);

    @Query("SELECT s FROM StockLot s WHERE s.inventoryItem.pharmacy.id = :pharmacyId " +
           "AND s.remainingQuantity > 0 AND s.expiryDate < :cutoffDate " +
           "ORDER BY s.expiryDate ASC")
    List<StockLot> findExpiringSoon(@Param("pharmacyId") UUID pharmacyId,
                                   @Param("cutoffDate") LocalDate cutoffDate);

    Page<StockLot> findByInventoryItemPharmacyId(UUID pharmacyId, Pageable pageable);

    /*
     * Atomic stock movements (#825 security finding 1). Read-modify-write on
     * the entity lost an update when two requests on DIFFERENT orders moved
     * the same lot at once (both read 10, one wrote 12 or 7, not 9). These
     * compute in the database; a decrement only applies when enough is left,
     * and the caller checks the row count. Bulk JPQL skips @PreUpdate, so
     * updatedAt is set here; the caller refreshes the managed copy.
     */

    /** Takes {@code quantity} off the lot when at least that much remains; 0 rows otherwise. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.remainingQuantity = l.remainingQuantity - :quantity, l.updatedAt = :now "
         + "WHERE l.id = :id AND l.remainingQuantity >= :quantity")
    int decrementRemaining(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                           @Param("now") LocalDateTime now);

    /**
     * QA D405: a positive count ADJUSTMENT, only while the lot stays within
     * what it received; 0 rows otherwise. Returns of dispensed stock use
     * {@link #incrementRemaining} (they put back what left).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.remainingQuantity = l.remainingQuantity + :quantity, l.updatedAt = :now "
         + "WHERE l.id = :id AND l.remainingQuantity + :quantity <= l.initialQuantity")
    int incrementRemainingWithinReceived(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                                         @Param("now") LocalDateTime now);

    /** Puts {@code quantity} back on the lot. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.remainingQuantity = l.remainingQuantity + :quantity, l.updatedAt = :now "
         + "WHERE l.id = :id")
    int incrementRemaining(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                           @Param("now") LocalDateTime now);

    /**
     * QA D2674 (r65): every lot of an item counted {@code factor} times finer (packs to units). The cost of one unit
     * is the pack's cost divided by {@code factor}. One atomic UPDATE, as every stock writer (#825 round 5).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.initialQuantity = l.initialQuantity * :factor, "
         + "l.remainingQuantity = l.remainingQuantity * :factor, l.unitCost = l.unitCost / :factor, l.updatedAt = :now "
         + "WHERE l.inventoryItem.id = :itemId")
    int scaleQuantitiesOfItem(@Param("itemId") UUID itemId, @Param("factor") BigDecimal factor,
                              @Param("now") LocalDateTime now);
}
