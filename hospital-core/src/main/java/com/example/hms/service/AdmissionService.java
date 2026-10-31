package com.example.hms.service;

import com.example.hms.payload.dto.AdmissionDischargeRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderExecutionRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.AdmissionRequestDTO;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.payload.dto.AdmissionUpdateRequestDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for admission management
 */
public interface AdmissionService {

    /**
     * Admit a patient
     */
    AdmissionResponseDTO admitPatient(AdmissionRequestDTO request);

    /**
     * Get admission by ID
     */
    AdmissionResponseDTO getAdmission(UUID admissionId);

    /**
     * Update admission details
     */
    AdmissionResponseDTO updateAdmission(UUID admissionId, AdmissionUpdateRequestDTO request);

    /**
     * Apply order sets to an admission
     */
    AdmissionResponseDTO applyOrderSets(UUID admissionId, AdmissionOrderExecutionRequestDTO request);

    /**
     * Discharge a patient
     */
    AdmissionResponseDTO dischargePatient(UUID admissionId, AdmissionDischargeRequestDTO request);

    /**
     * Cancel admission
     */
    void cancelAdmission(UUID admissionId);

    /**
     * Assign a structured bed to an admission (P0 #4)
     */
    AdmissionResponseDTO assignBed(UUID admissionId, UUID bedId);

    /**
     * Detach the admission's bed and make it available again
     */
    /** QA D1100: frees the inpatient's bed for a stated reason, audited with it. */
    AdmissionResponseDTO unassignBed(UUID admissionId, String reason);

    /**
     * Get admissions by patient
     */
    List<AdmissionResponseDTO> getAdmissionsByPatient(UUID patientId);

    /**
     * Get admissions by hospital
     */
    List<AdmissionResponseDTO> getAdmissionsByHospital(UUID hospitalId, String status, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * The admissions list: the acting hospital's (every hospital's for a
     * super-admin with no hospital in scope), narrowed by status, by an
     * admission-time window and, when {@code patientId} is given, to that
     * patient. QA D800: {@code patientId} was not a parameter, so
     * {@code GET /admissions?patientId=X} silently returned every patient's
     * stays and another patient's ACTIVE stay read as a second one of X's.
     */
    List<AdmissionResponseDTO> getAllAdmissions(String status, LocalDateTime startDate, LocalDateTime endDate,
                                                UUID patientId);

    /**
     * Get current admission for patient
     */
    AdmissionResponseDTO getCurrentAdmissionForPatient(UUID patientId);

    /**
     * Create order set template
     */
    AdmissionOrderSetResponseDTO createOrderSet(AdmissionOrderSetRequestDTO request);

    /**
     * Get order set by ID
     */
    AdmissionOrderSetResponseDTO getOrderSet(UUID orderSetId);

    /**
     * Get order sets by hospital
     */
    List<AdmissionOrderSetResponseDTO> getOrderSetsByHospital(UUID hospitalId, String admissionType);

    /**
     * Deactivate order set
     */
    void deactivateOrderSet(UUID orderSetId, String reason, UUID deactivatedByStaffId);
}
