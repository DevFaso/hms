package com.example.hms.repository.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.pharmacy.StockTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

@Repository
public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {

    Page<StockTransaction> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    Page<StockTransaction> findByStockLotId(UUID stockLotId, Pageable pageable);

    /** QA D270: the total moved through one lot by the given movement types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.stockLot.id = :lotId AND t.transactionType IN :types")
    BigDecimal sumQuantityForLot(@Param("lotId") UUID lotId,
                                 @Param("types") Collection<StockTransactionType> types);

    /** QA D270: the total moved for one item by the given movement types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.inventoryItem.id = :itemId AND t.transactionType IN :types")
    BigDecimal sumQuantityForItem(@Param("itemId") UUID itemId,
                                  @Param("types") Collection<StockTransactionType> types);

    /** QA D604: the total moved against one reference (a dispense) by the given types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.referenceId = :referenceId AND t.transactionType IN :types")
    BigDecimal sumQuantityForReference(@Param("referenceId") UUID referenceId,
                                       @Param("types") Collection<StockTransactionType> types);

    Page<StockTransaction> findByInventoryItemPharmacyId(UUID pharmacyId, Pageable pageable);

    @Query("SELECT t FROM StockTransaction t WHERE t.inventoryItem.pharmacy.id = :pharmacyId " +
           "AND t.createdAt BETWEEN :from AND :to ORDER BY t.createdAt DESC")
    Page<StockTransaction> findByPharmacyAndDateRange(@Param("pharmacyId") UUID pharmacyId,
                                                     @Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to,
                                                     Pageable pageable);

    Page<StockTransaction> findByTransactionType(StockTransactionType type, Pageable pageable);

    Page<StockTransaction> findByInventoryItemPharmacyIdAndTransactionType(
            UUID pharmacyId, StockTransactionType type, Pageable pageable);
}
