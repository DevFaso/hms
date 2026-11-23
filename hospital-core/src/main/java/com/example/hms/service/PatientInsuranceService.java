package com.example.hms.service;

import com.example.hms.security.ActingContext;
import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service boundary for managing patient insurance records.
 * At creation time, a patient is required; hospital/assignment is optional and can be linked later.
 */
public interface PatientInsuranceService {

    /**
     * Create a new insurance for a patient (patientId is REQUIRED).
     */
    PatientInsuranceResponseDTO addInsuranceToPatient(PatientInsuranceRequestDTO request, Locale locale);

    /**
     * Fetch a single insurance by its ID.
     */
    PatientInsuranceResponseDTO getPatientInsuranceById(UUID insuranceId, Locale locale);

    /**
     * List all insurances linked to a given patient.
     */
    List<PatientInsuranceResponseDTO> getInsurancesByPatientId(UUID patientId, Locale locale);

    /**
     * Full update (PUT semantics) of an insurance. Can also attach a patient if {@code patientId} is provided.
     * Hospital/assignment is not changed here—use the /link operation.
     */
    PatientInsuranceResponseDTO updatePatientInsurance(UUID insuranceId,
                                                       PatientInsuranceRequestDTO request,
                                                       Locale locale);

    /**
     * Delete an insurance by its ID.
     */
    void deletePatientInsurance(UUID insuranceId, Locale locale);

    /**
     * Link an existing insurance to a patient and optionally scope it to a hospital;
     * can also set/unset the primary flag. Acting context determines patient vs staff path.
     */
    PatientInsuranceResponseDTO linkPatientInsurance(UUID insuranceId,
                                                     LinkPatientInsuranceRequestDTO request,
                                                     ActingContext ctx,
                                                     Locale locale);

    @Transactional
    PatientInsuranceResponseDTO upsertAndLinkByInsuranceId(
        UUID insuranceId,
        LinkPatientInsuranceRequestDTO req,
        ActingContext ctx,
        Locale locale
    );

    PatientInsuranceResponseDTO upsertAndLinkByNaturalKey(
        LinkPatientInsuranceRequestDTO request,
        ActingContext ctx,
        Locale locale
    );

    /**
     * Billing-money plan PR 6: links a coverage row of the acting hospital to one of that hospital's payer plans, or
     * removes the link ({@code payerPlanId} null). Hospital admin, billing specialist and reception only.
     */
    PatientInsuranceResponseDTO setPayerPlan(UUID insuranceId, UUID payerPlanId, Locale locale);

    /**
     * Owner decision 2026-11-22: the patient's policies recorded at OTHER hospitals this one may read
     * (RecordAccessPolicy), by their identity only, « à vérifier ». Hospital admin, billing specialist and reception
     * of the acting hospital only.
     */
    List<com.example.hms.payload.dto.OtherHospitalInsuranceDTO> getOtherHospitalPolicies(UUID patientId);

    /**
     * Owner decision 2026-11-22: the acting hospital's OWN coverage made from a policy recorded at another hospital
     * (its identity fields only), unverified, with no plan; named after this hospital's payer when it has an agreement
     * with that insurer, else an identity-only record. The source row is never changed.
     */
    PatientInsuranceResponseDTO reuseOtherHospitalPolicy(UUID sourceInsuranceId);


}

