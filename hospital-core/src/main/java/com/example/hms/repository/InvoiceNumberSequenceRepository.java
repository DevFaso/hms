package com.example.hms.repository;

import com.example.hms.model.InvoiceNumberSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceNumberSequenceRepository extends JpaRepository<InvoiceNumberSequence, UUID> {

    /** The counter row, locked until the allocating transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InvoiceNumberSequence s WHERE s.hospitalId = :hospitalId AND s.sequenceYear = :year")
    Optional<InvoiceNumberSequence> findForUpdate(@Param("hospitalId") UUID hospitalId, @Param("year") int year);
}
