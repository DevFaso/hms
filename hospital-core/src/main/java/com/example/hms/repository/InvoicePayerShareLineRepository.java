package com.example.hms.repository;

import com.example.hms.model.InvoicePayerShareLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims PR 1 (V383, review of #1353): what each payer share claimed of each line. Every read names the
 * hospital.
 */
public interface InvoicePayerShareLineRepository extends JpaRepository<InvoicePayerShareLine, UUID> {

    /** The rows of these shares at one hospital (one page of « Parts assureurs » at once). */
    List<InvoicePayerShareLine> findByShareIdInAndHospitalId(Collection<UUID> shareIds, UUID hospitalId);
}
