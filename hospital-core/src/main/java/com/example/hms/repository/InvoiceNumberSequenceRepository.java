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

    /**
     * Review of #1246: the hospital row, share-locked while a number is
     * allocated. An edit of the hospital locks the same row for update before
     * it decides whether the code and the currency may still change
     * ({@code HospitalServiceImpl.updateHospital}), so a first number is either
     * committed before that decision or allocated after the edit, under the
     * code it saved. Shared: allocations do not wait for each other here.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT h FROM Hospital h WHERE h.id = :hospitalId")
    Optional<com.example.hms.model.Hospital> lockHospitalWhileNumbering(@Param("hospitalId") UUID hospitalId);
}
