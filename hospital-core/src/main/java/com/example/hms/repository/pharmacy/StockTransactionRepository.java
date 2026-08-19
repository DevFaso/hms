package com.example.hms.repository.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.pharmacy.StockTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {

    Page<StockTransaction> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    Page<StockTransaction> findByStockLotId(UUID stockLotId, Pageable pageable);

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
