package com.example.hms.repository;

import com.example.hms.model.MicroCultureCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MicroCultureCorrectionRepository extends JpaRepository<MicroCultureCorrection, UUID> {

    /** The report's corrections, newest first. */
    List<MicroCultureCorrection> findByCultureResult_IdOrderByCorrectedAtDesc(UUID cultureResultId);
}
