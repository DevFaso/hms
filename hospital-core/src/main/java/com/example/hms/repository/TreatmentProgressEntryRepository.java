package com.example.hms.repository;

import com.example.hms.model.TreatmentProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TreatmentProgressEntryRepository extends JpaRepository<TreatmentProgressEntry, UUID> {

    List<TreatmentProgressEntry> findByTreatmentPlanIdOrderByProgressDateDesc(UUID treatmentPlanId);

    boolean existsByTreatmentPlanIdAndPatientId(UUID treatmentPlanId, UUID patientId);
}
