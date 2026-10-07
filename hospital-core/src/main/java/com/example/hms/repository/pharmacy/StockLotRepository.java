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

    Page<StockLot> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    @Query("SELECT s FROM StockLot s WHERE s.inventoryItem.id = :inventoryItemId " +
           "AND s.remainingQuantity > 0 AND s.expiryDate >= CURRENT_DATE " +
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
     * Sets the remaining quantity outright: a count correction from the lot
     * edit endpoint. Last write wins, as a stock count does; it never rides
     * along with an unrelated edit of a stale copy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.remainingQuantity = :quantity, l.updatedAt = :now WHERE l.id = :id")
    int setRemaining(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                     @Param("now") LocalDateTime now);

    /** Puts {@code quantity} back on the lot. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("UPDATE StockLot l SET l.remainingQuantity = l.remainingQuantity + :quantity, l.updatedAt = :now "
         + "WHERE l.id = :id")
    int incrementRemaining(@Param("id") UUID id, @Param("quantity") BigDecimal quantity,
                           @Param("now") LocalDateTime now);
}
