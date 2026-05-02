package com.example.hms.repository;

import com.example.hms.model.CdsAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CdsAcknowledgementRepository extends JpaRepository<CdsAcknowledgement, UUID> {

    @Query("""
        SELECT a FROM CdsAcknowledgement a
        WHERE a.patient.id = :patientId
          AND a.expiresAt > :now
        """)
    List<CdsAcknowledgement> findActiveForPatient(@Param("patientId") UUID patientId,
                                                  @Param("now") LocalDateTime now);
}
