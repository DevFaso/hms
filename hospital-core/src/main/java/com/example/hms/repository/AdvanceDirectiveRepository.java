package com.example.hms.repository;

import com.example.hms.model.AdvanceDirective;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdvanceDirectiveRepository extends JpaRepository<AdvanceDirective, UUID> {

    List<AdvanceDirective> findByPatient_Id(UUID patientId);

    List<AdvanceDirective> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
}
