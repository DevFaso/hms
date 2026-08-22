package com.example.hms.repository;

import com.example.hms.model.PatientGuarantor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientGuarantorRepository extends JpaRepository<PatientGuarantor, UUID> {

    @Query("""
            SELECT g FROM PatientGuarantor g
            WHERE g.patient.id = :patientId
                AND (:hospitalId IS NULL OR g.hospital.id = :hospitalId)
            ORDER BY g.primary DESC, g.createdAt ASC
    """)
    List<PatientGuarantor> findForPatient(@Param("patientId") UUID patientId,
                                          @Param("hospitalId") UUID hospitalId);

    List<PatientGuarantor> findByPatient_IdAndHospital_IdAndActiveTrue(UUID patientId, UUID hospitalId);
}
