package com.example.hms.repository;

import com.example.hms.model.InvoicePayerShareLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** The rows of one share at one hospital. */
    List<InvoicePayerShareLine> findByShareIdAndHospitalId(UUID shareId, UUID hospitalId);

    /**
     * Review of #1353 (F1): removes one share's rows at one hospital before they are written again. A bulk delete
     * runs at once, so the rows written next never meet the old ones on {@code uq_payer_share_line}.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM InvoicePayerShareLine l WHERE l.shareId = :shareId AND l.hospitalId = :hospitalId")
    int deleteByShareAndHospital(@Param("shareId") UUID shareId, @Param("hospitalId") UUID hospitalId);
}
