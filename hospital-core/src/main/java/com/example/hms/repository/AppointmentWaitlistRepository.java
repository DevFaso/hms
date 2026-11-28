package com.example.hms.repository;

import com.example.hms.model.AppointmentWaitlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentWaitlistRepository extends JpaRepository<AppointmentWaitlist, UUID> {

    List<AppointmentWaitlist> findByHospital_IdAndStatusOrderByCreatedAtAsc(UUID hospitalId, String status);

    @Query("""
        SELECT w FROM AppointmentWaitlist w
         WHERE w.hospital.id = :hospitalId
           AND (:departmentId IS NULL OR w.department.id = :departmentId)
           AND (:status IS NULL OR w.status = :status)
         ORDER BY w.createdAt ASC
        """)
    List<AppointmentWaitlist> findByHospitalFiltered(
        @Param("hospitalId") UUID hospitalId,
        @Param("departmentId") UUID departmentId,
        @Param("status") String status
    );

    Optional<AppointmentWaitlist> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    /** QA D4229: whether the patient already has a request in one of these statuses for this department here. */
    boolean existsByHospital_IdAndPatient_IdAndDepartment_IdAndStatusIn(UUID hospitalId, UUID patientId,
                                                                       UUID departmentId,
                                                                       java.util.Collection<String> statuses);

    /** Lapsed offers, for the reconciliation sweep (P3 #22). Unscoped — system actor. */
    List<AppointmentWaitlist> findByStatusAndOfferExpiresAtBefore(
        String status, java.time.LocalDateTime cutoff);
}
