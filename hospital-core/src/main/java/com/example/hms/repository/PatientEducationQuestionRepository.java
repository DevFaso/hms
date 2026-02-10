package com.example.hms.repository;

import com.example.hms.model.education.PatientEducationQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientEducationQuestionRepository extends JpaRepository<PatientEducationQuestion, UUID> {

    List<PatientEducationQuestion> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<PatientEducationQuestion> findByPatientIdAndIsAnsweredFalseOrderByCreatedAtDesc(UUID patientId);

    List<PatientEducationQuestion> findByResourceIdOrderByCreatedAtDesc(UUID resourceId);

    List<PatientEducationQuestion> findByIsAnsweredFalseAndHospitalIdOrderByIsUrgentDescCreatedAtDesc(
        UUID hospitalId
    );

    List<PatientEducationQuestion> findByHospitalIdAndIsUrgentTrueAndIsAnsweredFalseOrderByCreatedAtAsc(
        UUID hospitalId
    );

    @Query("SELECT peq FROM PatientEducationQuestion peq WHERE peq.hospitalId = :hospitalId AND peq.isAnswered = false AND " +
           "peq.requiresInPersonDiscussion = true AND peq.appointmentScheduled = false " +
           "ORDER BY peq.isUrgent DESC, peq.createdAt ASC")
    List<PatientEducationQuestion> findUnansweredRequiringAppointment(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT peq FROM PatientEducationQuestion peq WHERE peq.answeredByStaffId = :staffId " +
           "ORDER BY peq.answeredAt DESC")
    List<PatientEducationQuestion> findAnsweredByProvider(@Param("staffId") UUID staffId);
}
