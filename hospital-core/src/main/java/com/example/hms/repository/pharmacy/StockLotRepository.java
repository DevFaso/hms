package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.StockLot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StockLotRepository extends JpaRepository<StockLot, UUID> {

    Page<StockLot> findByInventoryItemId(UUID inventoryItemId, Pageable pageable);

    @Query("SELECT s FROM StockLot s WHERE s.inventoryItem.id = :inventoryItemId " +
           "AND s.remainingQuantity > 0 AND s.expiryDate >= CURRENT_DATE " +
           "ORDER BY s.expiryDate ASC")
    List<StockLot> findAvailableLotsByFEFO(@Param("inventoryItemId") UUID inventoryItemId);
}
