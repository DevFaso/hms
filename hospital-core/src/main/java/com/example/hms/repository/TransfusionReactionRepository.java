package com.example.hms.repository;

import com.example.hms.model.TransfusionReaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransfusionReactionRepository extends JpaRepository<TransfusionReaction, UUID> {

    List<TransfusionReaction> findByAdministration_IdOrderByOnsetAtDesc(UUID administrationId);

    List<TransfusionReaction> findByPatient_IdAndHospital_IdOrderByOnsetAtDesc(UUID patientId, UUID hospitalId);

    List<TransfusionReaction> findByHospital_IdOrderByOnsetAtDesc(UUID hospitalId);

    /**
     * QA D4151: the newest reaction of one of these severities on the patient's record, at any hospital (a
     * severe reaction follows the patient, like the antibody flag).
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"hospital"})
    java.util.Optional<TransfusionReaction> findFirstByPatient_IdAndSeverityInOrderByOnsetAtDesc(
        UUID patientId, java.util.Collection<com.example.hms.enums.TransfusionReactionSeverity> severities);
}
