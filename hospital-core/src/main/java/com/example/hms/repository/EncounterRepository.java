package com.example.hms.repository;

import com.example.hms.model.Encounter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import com.example.hms.enums.EncounterStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EncounterRepository
    extends JpaRepository<Encounter, UUID>, JpaSpecificationExecutor<Encounter> {

    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH p.user
        JOIN FETCH e.staff s
        JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.appointment a
        WHERE p.id = :patientId
    """)
    List<Encounter> findByPatient_Id(@org.springframework.data.repository.query.Param("patientId") UUID patientId);

    // Encounters by STAFF/DOCTOR (also fully fetched)
    @org.springframework.data.jpa.repository.Query("""
        SELECT e FROM Encounter e
        JOIN FETCH e.patient p
        JOIN FETCH p.user
        JOIN FETCH e.staff s
        JOIN FETCH s.user
        LEFT JOIN FETCH e.department d
        LEFT JOIN FETCH e.hospital h
        LEFT JOIN FETCH e.appointment a
        WHERE s.id = :staffId
    """)
    List<Encounter> findByStaff_Id(@org.springframework.data.repository.query.Param("staffId") UUID staffId);

    // Advanced filtering for performance
    Page<Encounter> findByHospital_IdAndStatus(UUID hospitalId, EncounterStatus status, Pageable pageable);

    Page<Encounter> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);

    Page<Encounter> findByStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId, Pageable pageable);

    Optional<Encounter> findFirstByPatient_IdAndStaff_IdAndHospital_IdOrderByEncounterDateDesc(
        UUID patientId,
        UUID staffId,
        UUID hospitalId);

    // Date range filtering
    Page<Encounter> findByHospital_IdAndEncounterDateBetween(UUID hospitalId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    // Combined filters for flexible search
    Page<Encounter> findByHospital_IdAndPatient_IdAndStatusAndEncounterDateBetween(
        UUID hospitalId,
        UUID patientId,
        EncounterStatus status,
        LocalDateTime from,
        LocalDateTime to,
        Pageable pageable
    );

    boolean existsById(UUID id);

    // Duplicate guards
    boolean existsByHospital_IdAndPatient_IdAndEncounterDate(
        UUID hospitalId, UUID patientId, LocalDateTime encounterDate
    );

    boolean existsByHospital_IdAndPatient_IdAndAppointment_IdAndEncounterDate(
        UUID hospitalId, UUID patientId, UUID appointmentId, LocalDateTime encounterDate
    );

    Optional<Encounter> findByIdAndHospital_Id(@org.springframework.lang.NonNull UUID id, UUID hospitalId);

    Optional<Encounter> findByCode(String code);
}
