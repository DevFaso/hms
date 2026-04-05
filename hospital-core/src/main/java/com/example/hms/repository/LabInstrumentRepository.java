package com.example.hms.repository;

import com.example.hms.enums.InstrumentStatus;
import com.example.hms.model.LabInstrument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabInstrumentRepository extends JpaRepository<LabInstrument, UUID> {

    Page<LabInstrument> findByHospitalIdAndActiveTrue(UUID hospitalId, Pageable pageable);

    Optional<LabInstrument> findByIdAndActiveTrue(UUID id);

    boolean existsByHospitalIdAndSerialNumber(UUID hospitalId, String serialNumber);

    @Query("SELECT i FROM LabInstrument i WHERE i.hospital.id = :hospitalId "
         + "AND i.active = true AND i.status = :status")
    Page<LabInstrument> findByHospitalIdAndStatus(
        @Param("hospitalId") UUID hospitalId,
        @Param("status") InstrumentStatus status,
        Pageable pageable);

    @Query("SELECT i FROM LabInstrument i WHERE i.hospital.id = :hospitalId "
         + "AND i.active = true "
         + "AND (i.nextCalibrationDate <= :threshold OR i.nextMaintenanceDate <= :threshold)")
    List<LabInstrument> findOverdueInstruments(
        @Param("hospitalId") UUID hospitalId,
        @Param("threshold") LocalDate threshold);

    long countByHospitalIdAndActiveTrue(UUID hospitalId);

    long countByHospitalIdAndActiveTrueAndStatus(UUID hospitalId, InstrumentStatus status);
}
