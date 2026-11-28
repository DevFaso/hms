package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.StockCount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCountRepository extends JpaRepository<StockCount, UUID> {
    Optional<StockCount> findByHospitalIdAndClientRequestId(UUID hospitalId, UUID clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StockCount c WHERE c.id = :id AND c.hospital.id = :hospitalId")
    Optional<StockCount> findLocked(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT c FROM StockCount c WHERE c.hospital.id = :hospitalId "
        + "AND c.inventoryItem.pharmacy.id = :pharmacyId ORDER BY c.recordedAt DESC, c.id DESC")
    Page<StockCount> findForPharmacy(@Param("hospitalId") UUID hospitalId,
        @Param("pharmacyId") UUID pharmacyId, Pageable pageable);

    /** QA D4191: a recount closes the counts of the same item (no lot) that still wait. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockCount c SET c.closedAt = :at, c.closedBy = :by, c.closedByName = :name, c.closeReason = :reason "
        + "WHERE c.hospital.id = :hospitalId AND c.inventoryItem.id = :itemId AND c.stockLot IS NULL "
        + "AND c.appliedAt IS NULL AND c.closedAt IS NULL AND c.id <> :keepId")
    int closeOpenItemCounts(@Param("hospitalId") UUID hospitalId, @Param("itemId") UUID itemId, @Param("keepId") UUID keepId,
        @Param("at") java.time.LocalDateTime at, @Param("by") com.example.hms.model.User by,
        @Param("name") String name, @Param("reason") String reason);

    /** QA D4191: a recount closes the counts of the same lot that still wait. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockCount c SET c.closedAt = :at, c.closedBy = :by, c.closedByName = :name, c.closeReason = :reason "
        + "WHERE c.hospital.id = :hospitalId AND c.inventoryItem.id = :itemId AND c.stockLot.id = :lotId "
        + "AND c.appliedAt IS NULL AND c.closedAt IS NULL AND c.id <> :keepId")
    int closeOpenLotCounts(@Param("hospitalId") UUID hospitalId, @Param("itemId") UUID itemId, @Param("lotId") UUID lotId,
        @Param("keepId") UUID keepId, @Param("at") java.time.LocalDateTime at, @Param("by") com.example.hms.model.User by,
        @Param("name") String name, @Param("reason") String reason);
}
