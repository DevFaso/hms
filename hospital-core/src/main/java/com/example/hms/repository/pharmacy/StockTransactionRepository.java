package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.StockTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {

    Page<StockTransaction> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    Page<StockTransaction> findByStockLotId(UUID stockLotId, Pageable pageable);
}
