package com.example.hms.repository;

import com.example.hms.model.InvoiceLineRemoval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** PR 10 review (V309, QA D2703): the lines taken off draft invoices, read per hospital for the day report. */
public interface InvoiceLineRemovalRepository extends JpaRepository<InvoiceLineRemoval, UUID> {

    /** One hospital's removals in [start, end), oldest first. */
    List<InvoiceLineRemoval> findByHospitalIdAndRemovedAtGreaterThanEqualAndRemovedAtLessThanOrderByRemovedAtAsc(
        UUID hospitalId, LocalDateTime start, LocalDateTime end);
}
