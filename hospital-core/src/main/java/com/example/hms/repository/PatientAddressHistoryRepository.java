package com.example.hms.repository;

import com.example.hms.model.PatientAddressHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientAddressHistoryRepository extends JpaRepository<PatientAddressHistory, UUID> {

    /** Most recent move first — each row was valid until its createdAt. */
    List<PatientAddressHistory> findByPatient_IdOrderByCreatedAtDesc(UUID patientId);
}
