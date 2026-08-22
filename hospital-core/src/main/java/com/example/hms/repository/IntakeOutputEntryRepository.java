package com.example.hms.repository;

import com.example.hms.model.IntakeOutputEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface IntakeOutputEntryRepository extends JpaRepository<IntakeOutputEntry, UUID> {

    @Query("""
            SELECT e FROM IntakeOutputEntry e
            WHERE e.patient.id = :patientId
                AND (:hospitalId IS NULL OR e.hospital.id = :hospitalId)
                AND e.observationTime >= :from
                AND e.observationTime <= :to
            ORDER BY e.observationTime ASC
    """)
    List<IntakeOutputEntry> findWindow(@Param("patientId") UUID patientId,
                                       @Param("hospitalId") UUID hospitalId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);
}
