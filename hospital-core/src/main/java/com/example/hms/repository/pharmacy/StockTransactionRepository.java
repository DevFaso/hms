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

    /** An unreferenced return cannot reverse a paired transfer without debiting its receiving store. */
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM StockTransaction t "
         + "WHERE t.inventoryItem.id = :itemId AND (:withLot = false OR t.stockLot.id = :lotId) "
         + "AND t.transactionType = com.example.hms.enums.StockTransactionType.TRANSFER "
         + "AND EXISTS (SELECT r.id FROM StockTransaction r WHERE r.referenceId = t.id "
         + "AND r.transactionType = com.example.hms.enums.StockTransactionType.RECEIPT)")
    boolean hasPairedTransfer(@Param("itemId") UUID itemId, @Param("withLot") boolean withLot,
                              @Param("lotId") UUID lotId);

    /** The counterpart receipt of one atomic inter-store transfer. */
    java.util.Optional<StockTransaction> findByReferenceIdAndTransactionType(UUID referenceId,
                                                                            StockTransactionType transactionType);

    Page<StockTransaction> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    Page<StockTransaction> findByStockLotId(UUID stockLotId, Pageable pageable);

    /**
     * QA D3415 (r70): what each item and lot of a pharmacy moved by the given types, as
     * {@code [itemId, lotId (null for no lot), type, total]} rows: the quarantine read.
     */
    @Query("SELECT t.inventoryItem.id, l.id, t.transactionType, COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "LEFT JOIN t.stockLot l "
         + "WHERE t.inventoryItem.pharmacy.id = :pharmacyId AND t.transactionType IN :types "
         + "GROUP BY t.inventoryItem.id, l.id, t.transactionType")
    java.util.List<Object[]> sumByItemLotAndTypeForPharmacy(@Param("pharmacyId") UUID pharmacyId,
                                                            @Param("types") Collection<StockTransactionType> types);

    /** QA D3415: what one item moved by the given types with no lot. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.inventoryItem.id = :itemId AND t.stockLot IS NULL AND t.transactionType IN :types")
    BigDecimal sumQuantityForItemWithoutLot(@Param("itemId") UUID itemId,
                                            @Param("types") Collection<StockTransactionType> types);

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

    /** QA D668: what unreferenced movements of the given types moved for one lot. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.stockLot.id = :lotId AND t.referenceId IS NULL AND t.transactionType IN :types")
    BigDecimal sumUnreferencedQuantityForLot(@Param("lotId") UUID lotId,
                                             @Param("types") Collection<StockTransactionType> types);

    /** QA D668: what unreferenced movements of the given types moved for one item. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.inventoryItem.id = :itemId AND t.referenceId IS NULL AND t.transactionType IN :types")
    BigDecimal sumUnreferencedQuantityForItem(@Param("itemId") UUID itemId,
                                              @Param("types") Collection<StockTransactionType> types);

    /** QA D604: the total moved against one reference (a dispense) by the given types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.referenceId = :referenceId AND t.transactionType IN :types")
    BigDecimal sumQuantityForReference(@Param("referenceId") UUID referenceId,
                                       @Param("types") Collection<StockTransactionType> types);

    /** QA D2676 (r65): the total moved against one reference (a sale) on one lot by the given types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.referenceId = :referenceId AND t.stockLot.id = :lotId AND t.transactionType IN :types")
    BigDecimal sumQuantityForReferenceAndLot(@Param("referenceId") UUID referenceId, @Param("lotId") UUID lotId,
                                             @Param("types") Collection<StockTransactionType> types);

    /** QA D2676 (r65): the total moved against one reference (a sale) on one item by the given types. */
    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.referenceId = :referenceId AND t.inventoryItem.id = :itemId AND t.transactionType IN :types")
    BigDecimal sumQuantityForReferenceAndItem(@Param("referenceId") UUID referenceId, @Param("itemId") UUID itemId,
                                              @Param("types") Collection<StockTransactionType> types);

    /** QA D2673 (r65): every movement of one pharmacy in [from, to), oldest first, with its lot and item. */
    @Query("SELECT t FROM StockTransaction t JOIN FETCH t.inventoryItem i LEFT JOIN FETCH t.stockLot "
         + "WHERE i.pharmacy.id = :pharmacyId AND t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt")
    java.util.List<StockTransaction> findForDay(@Param("pharmacyId") UUID pharmacyId,
                                                @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * QA D932: {@link #sumQuantityForReference} for several references in one
     * query, as {@code [referenceId, total]} rows; a reference with nothing
     * moved has no row.
     */
    @Query("SELECT t.referenceId, COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.referenceId IN :referenceIds AND t.transactionType IN :types GROUP BY t.referenceId")
    java.util.List<Object[]> sumQuantityByReferences(@Param("referenceIds") Collection<UUID> referenceIds,
                                           @Param("types") Collection<StockTransactionType> types);

    /**
     * QA D967: the controlled-drug register's movements - the given items'
     * ledger rows in [from, to), oldest first, with who recorded and who
     * witnessed each and the lot.
     */
    @Query("SELECT t FROM StockTransaction t LEFT JOIN FETCH t.performedByUser LEFT JOIN FETCH t.witnessedByUser "
         + "LEFT JOIN FETCH t.stockLot WHERE t.inventoryItem.id IN :itemIds "
         + "AND t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt ASC, t.id ASC")
    java.util.List<StockTransaction> findRegisterRows(@Param("itemIds") Collection<UUID> itemIds,
                                                     @Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to,
                                                     Pageable pageable);

    /**
     * QA D967: what the ledger moved for each item and type before a moment,
     * as {@code [itemId, type, total]} rows - the register's opening balance.
     */
    @Query("SELECT t.inventoryItem.id, t.transactionType, COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "WHERE t.inventoryItem.id IN :itemIds AND t.createdAt < :before "
         + "GROUP BY t.inventoryItem.id, t.transactionType")
    java.util.List<Object[]> sumByItemAndTypeBefore(@Param("itemIds") Collection<UUID> itemIds,
                                                    @Param("before") LocalDateTime before);

    /**
     * Review of #1239 (per-lot balance): what the ledger moved on each lot of one pharmacy before a moment, as
     * {@code [lotId, type, total]} rows. Signed and summed, it is the lot's balance by the ledger at that moment.
     */
    @Query("SELECT l.id, t.transactionType, COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "JOIN t.stockLot l JOIN t.inventoryItem i WHERE i.pharmacy.id = :pharmacyId AND t.createdAt < :before "
         + "GROUP BY l.id, t.transactionType")
    java.util.List<Object[]> sumByLotAndTypeBefore(@Param("pharmacyId") UUID pharmacyId,
                                                   @Param("before") LocalDateTime before);

    /** Review of #1239: the same, for the movements made at or after a moment (what happened since a day closed). */
    @Query("SELECT l.id, t.transactionType, COALESCE(SUM(t.quantity), 0) FROM StockTransaction t "
         + "JOIN t.stockLot l JOIN t.inventoryItem i WHERE i.pharmacy.id = :pharmacyId AND t.createdAt >= :from "
         + "GROUP BY l.id, t.transactionType")
    java.util.List<Object[]> sumByLotAndTypeFrom(@Param("pharmacyId") UUID pharmacyId,
                                                 @Param("from") LocalDateTime from);

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
