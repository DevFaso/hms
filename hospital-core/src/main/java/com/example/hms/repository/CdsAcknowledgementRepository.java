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

    /** QA D1194 (round 22): one clinician's live acknowledgements on one patient. */
    @Query("""
        SELECT a FROM CdsAcknowledgement a
        WHERE a.patient.id = :patientId
          AND a.user.id = :userId
          AND a.expiresAt > :now
        """)
    List<CdsAcknowledgement> findActiveForPatientAndUser(@Param("patientId") UUID patientId,
                                                         @Param("userId") UUID userId,
                                                         @Param("now") LocalDateTime now);
}
