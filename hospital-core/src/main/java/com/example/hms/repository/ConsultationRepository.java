package com.example.hms.repository;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {

    List<Consultation> findByPatient_IdOrderByRequestedAtDesc(UUID patientId);

    List<Consultation> findByHospital_IdAndStatusOrderByRequestedAtDesc(UUID hospitalId, ConsultationStatus status);

    List<Consultation> findByRequestingProvider_IdOrderByRequestedAtDesc(UUID providerId);

    List<Consultation> findByConsultant_IdAndStatusOrderByRequestedAtDesc(UUID consultantId, ConsultationStatus status);

    List<Consultation> findByConsultant_IdOrderByRequestedAtDesc(UUID consultantId);

    List<Consultation> findByStatusOrderByRequestedAtDesc(ConsultationStatus status);

    List<Consultation> findAllByOrderByRequestedAtDesc();

    @Query("SELECT c FROM Consultation c WHERE c.hospital.id = :hospitalId " +
           "AND c.status IN :statuses ORDER BY c.urgency DESC, c.requestedAt ASC")
    List<Consultation> findByHospitalAndStatuses(
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") List<ConsultationStatus> statuses
    );

    @Query("SELECT c FROM Consultation c WHERE c.slaDueBy < :now AND c.status NOT IN :completedStatuses")
    List<Consultation> findOverdueConsultations(
        @Param("now") LocalDateTime now,
        @Param("completedStatuses") List<ConsultationStatus> completedStatuses
    );
}
