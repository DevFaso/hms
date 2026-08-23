package com.example.hms.repository.scheduling;

import com.example.hms.enums.RecallStatus;
import com.example.hms.model.scheduling.PatientRecall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRecallRepository extends JpaRepository<PatientRecall, UUID> {

    @Query("""
            SELECT r FROM PatientRecall r
            WHERE r.hospital.id = :hospitalId
                AND (:status IS NULL OR r.status = :status)
                AND (:patientId IS NULL OR r.patient.id = :patientId)
            ORDER BY r.dueDate ASC
    """)
    List<PatientRecall> findForHospital(@Param("hospitalId") UUID hospitalId,
                                        @Param("status") RecallStatus status,
                                        @Param("patientId") UUID patientId);

    /**
     * The notification sweep's selection: PENDING, coming due within the
     * lead window, not yet notified. UNSCOPED across hospitals — the sweep
     * is a system actor, the V112 reminder idiom.
     */
    @Query("""
            SELECT r FROM PatientRecall r
            WHERE r.status = com.example.hms.enums.RecallStatus.PENDING
                AND r.notifiedAt IS NULL
                AND r.dueDate <= :windowEnd
            ORDER BY r.dueDate ASC
    """)
    List<PatientRecall> findAwaitingNotification(@Param("windowEnd") LocalDate windowEnd);

    /** Scoped single-row load — the 404-not-403 tenancy idiom. */
    Optional<PatientRecall> findByIdAndHospital_Id(UUID id, UUID hospitalId);
}
