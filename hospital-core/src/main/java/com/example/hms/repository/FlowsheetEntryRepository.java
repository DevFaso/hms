package com.example.hms.repository;

import com.example.hms.enums.FlowsheetType;
import com.example.hms.model.FlowsheetEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FlowsheetEntryRepository extends JpaRepository<FlowsheetEntry, UUID> {

    List<FlowsheetEntry> findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId);

    List<FlowsheetEntry> findByPatient_IdAndHospital_IdAndTypeOrderByRecordedAtDesc(
        UUID patientId, UUID hospitalId, FlowsheetType type);

    List<FlowsheetEntry> findByHospital_IdAndTypeAndRecordedAtAfterOrderByRecordedAtDesc(
        UUID hospitalId, FlowsheetType type, LocalDateTime since);

    List<FlowsheetEntry> findByPatient_IdAndHospital_IdAndRecordedAtAfterOrderByRecordedAtDesc(
        UUID patientId, UUID hospitalId, LocalDateTime since);
}
