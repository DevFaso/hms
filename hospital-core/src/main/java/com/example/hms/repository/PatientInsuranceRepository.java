package com.example.hms.repository;

import com.example.hms.model.PatientInsurance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientInsuranceRepository extends JpaRepository<PatientInsurance, UUID> {

    List<PatientInsurance> findByPatient_Id(UUID patientId);

    List<PatientInsurance> findByPatient_IdAndAssignment_Hospital_Id(UUID patientId, UUID hospitalId);

    /**
     * Owner decision 2026-11-22: the patient's coverage rows held by the hospitals named, which the caller has
     * already narrowed to the ones RecordAccessPolicy lets it read. Untagged (shared) rows are not among them.
     */
    List<PatientInsurance> findByPatient_IdAndAssignment_Hospital_IdIn(UUID patientId,
                                                                      java.util.Collection<UUID> hospitalIds);

    /** The patient's shared (legacy, untagged) rows, which every hospital sees (PatientInsurance#isVisibleAt). */
    List<PatientInsurance> findByPatient_IdAndAssignmentIsNull(UUID patientId);

    /** QA D1590: the patient's policy by payer and member number, as THIS hospital holds it. */
    Optional<PatientInsurance> findFirstByPatient_IdAndAssignment_Hospital_IdAndProviderNameIgnoreCaseAndPolicyNumberIgnoreCase(
        UUID patientId, UUID hospitalId, String providerName, String policyNumber);

    /** QA D1590: the same policy recorded for the patient but tagged to no hospital yet. */
    Optional<PatientInsurance> findFirstByPatient_IdAndAssignmentIsNullAndProviderNameIgnoreCaseAndPolicyNumberIgnoreCase(
        UUID patientId, String providerName, String policyNumber);
    Optional<PatientInsurance> findByIdAndAssignment_Hospital_Id(UUID id, UUID hospitalId);

    /**
     * Billing-money plan PR 7: the coverage row, locked (SELECT ... FOR UPDATE on that row only). The payer's share of
     * an invoice is computed under this lock, so two invoices issued at once cannot both use what is left of the
     * coverage's annual ceiling. The caller found the row through a hospital-scoped read first
     * ({@link #findByPatient_IdAndAssignment_Hospital_Id}); it never reaches another hospital's coverage.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PatientInsurance i WHERE i.id = :id")
    Optional<PatientInsurance> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
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

    /**
     * Review of #1131: the desk's natural-key link finds the policy as THIS
     * hospital holds it. The unscoped finder it replaced returned another
     * hospital's row, which the link then re-tagged to the caller's hospital.
     * {@code PatientInsuranceScopedLookupGuardTest} refuses an unscoped finder.
     */
    Optional<PatientInsurance> findFirstByPatient_IdAndAssignment_Hospital_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
        UUID patientId, UUID hospitalId, String payerCode, String policyNumber);

    /** The same policy recorded for the patient but tagged to no hospital yet. */
    Optional<PatientInsurance> findFirstByPatient_IdAndAssignmentIsNullAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
        UUID patientId, String payerCode, String policyNumber);

boolean existsByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
    UUID patientId, String payerCode, String policyNumber
);

}
