package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PharmacyReceiptSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyReceiptSequenceRepository extends JpaRepository<PharmacyReceiptSequence, UUID> {

    /** QA D1032: the counter row, locked until the allocating transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PharmacyReceiptSequence s WHERE s.hospitalId = :hospitalId AND s.sequenceYear = :year")
    Optional<PharmacyReceiptSequence> findForUpdate(@Param("hospitalId") UUID hospitalId, @Param("year") int year);
}
