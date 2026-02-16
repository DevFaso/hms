package com.example.hms.repository;

import com.example.hms.model.NursingNote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NursingNoteRepository extends JpaRepository<NursingNote, UUID> {

    @EntityGraph(attributePaths = {"educationEntries", "interventionEntries", "addenda"})
    List<NursingNote> findTop50ByPatient_IdAndHospital_IdOrderByCreatedAtDesc(UUID patientId, UUID hospitalId);

    @EntityGraph(attributePaths = {"educationEntries", "interventionEntries", "addenda"})
    List<NursingNote> findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(UUID patientId, UUID hospitalId);

    @EntityGraph(attributePaths = {"educationEntries", "interventionEntries", "addenda"})
    Optional<NursingNote> findByIdAndHospital_Id(UUID id, UUID hospitalId);
}
