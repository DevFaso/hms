package com.example.hms.repository;

import com.example.hms.model.TransfusionReaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransfusionReactionRepository extends JpaRepository<TransfusionReaction, UUID> {

    List<TransfusionReaction> findByAdministration_IdOrderByOnsetAtDesc(UUID administrationId);

    List<TransfusionReaction> findByPatient_IdAndHospital_IdOrderByOnsetAtDesc(UUID patientId, UUID hospitalId);

    List<TransfusionReaction> findByHospital_IdOrderByOnsetAtDesc(UUID hospitalId);
}
