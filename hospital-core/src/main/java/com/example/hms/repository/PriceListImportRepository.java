package com.example.hms.repository;

import com.example.hms.enums.PriceListImportStatus;
import com.example.hms.model.PriceListImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Billing-money plan PR 4 (V304): the trace of the price-list CSV imports. Every read names the hospital: another
 * hospital's file is never "already imported" here.
 */
public interface PriceListImportRepository extends JpaRepository<PriceListImport, UUID> {

    /** The earlier APPLIED import of the same file at this hospital, if any (at most one, V304 partial UNIQUE). */
    Optional<PriceListImport> findFirstByHospitalIdAndSha256AndStatus(UUID hospitalId, String sha256,
                                                                      PriceListImportStatus status);
}
