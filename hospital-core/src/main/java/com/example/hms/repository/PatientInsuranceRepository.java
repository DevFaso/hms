package com.example.hms.repository;

import com.example.hms.model.PatientInsurance;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Repository
public interface PatientInsuranceRepository extends JpaRepository<PatientInsurance, UUID> {

    List<PatientInsurance> findByPatient_Id(UUID patientId);

    List<PatientInsurance> findByPatient_IdAndAssignment_Hospital_Id(UUID patientId, UUID hospitalId);
    Optional<PatientInsurance> findByIdAndAssignment_Hospital_Id(UUID id, UUID hospitalId);
    boolean existsByPatient_IdAndPolicyNumberIgnoreCaseAndAssignment_Hospital_Id(UUID patientId, String policyNumber,
                                                                                 UUID hospitalId);
    Optional<PatientInsurance> findByPatient_IdAndPrimaryTrueAndAssignment_Hospital_Id(UUID patientId,
                                                                                       UUID hospitalId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update PatientInsurance i
       set i.primary = false
     where i.patient.id = :patientId and i.id <> :keepId
""")
    int unsetOtherPrimariesForPatient(UUID patientId, UUID keepId);


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update PatientInsurance i
           set i.primary = false
         where i.patient.id = :patientId
           and i.id <> :keepId
           and i.assignment.id in (
               select a.id from UserRoleHospitalAssignment a
               where a.hospital.id = :hospitalId
           )
    """)
    int unsetOtherPrimariesForPatientInHospital(UUID patientId, UUID keepId, UUID hospitalId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update PatientInsurance i
           set i.primary = false
         where i.patient.id = :patientId
           and i.assignment.id in (
               select a.id from UserRoleHospitalAssignment a
               where a.hospital.id = :hospitalId
           )
    """)
    int unsetAllPrimaries(UUID patientId, UUID hospitalId);

    Optional<PatientInsurance> findByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
    UUID patientId, String payerCode, String policyNumber
);

boolean existsByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
    UUID patientId, String payerCode, String policyNumber
);

}
