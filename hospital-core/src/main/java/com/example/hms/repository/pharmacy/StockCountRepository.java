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
}
