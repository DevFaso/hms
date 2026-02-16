package com.example.hms.service;

import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DoctorPatientRecordDTO;
import com.example.hms.payload.dto.DoctorPatientRecordRequestDTO;
import com.example.hms.payload.dto.PatientAllergyRequestDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import com.example.hms.payload.dto.PatientDiagnosisRequestDTO;
import com.example.hms.payload.dto.PatientDiagnosisUpdateRequestDTO;
import com.example.hms.payload.dto.PatientProblemResponseDTO;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientSearchCriteria;
import com.example.hms.payload.dto.PatientTimelineAccessRequestDTO;
import com.example.hms.payload.dto.PatientTimelineResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public interface PatientService {

    List<PatientResponseDTO> getAllPatients(UUID hospitalId, Locale locale);

    PatientResponseDTO getPatientById(UUID id, UUID hospitalId, Locale locale);

    PatientResponseDTO createPatient(PatientRequestDTO patientRequestDTO, Locale locale);

    PatientResponseDTO updatePatient(UUID id, PatientRequestDTO patientRequestDTO, Locale locale);

    PatientResponseDTO patchPatient(UUID id, PatientProfileUpdateRequestDTO request, Locale locale);

    void deletePatient(UUID id, Locale locale);

    @Transactional(readOnly = true)
    List<PatientResponseDTO> searchPatients(PatientSearchCriteria criteria, int page, int size, Locale locale);

    // ✅ New Methods for Hospital Context
    boolean isRegisteredInHospital(UUID patientId, UUID hospitalId);

    Optional<String> getMrnForHospital(UUID patientId, UUID hospitalId);

    List<PatientResponseDTO> getPatientsByHospital(UUID hospitalId, Locale locale);

    Page<PatientResponseDTO> getPatientPageByHospital(UUID hospitalId, Boolean activeOnly, Pageable pageable);

    void backfillMissingPatients();

    PatientResponseDTO createPatientByStaff(PatientRequestDTO dto, Locale locale);

    List<PatientResponseDTO> lookupPatients(
        String identifier,
        String email,
        String phone,
        String username,
        String mrn,
        UUID hospitalId,
        Locale locale
    );

    PatientTimelineResponseDTO getDoctorTimeline(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        PatientTimelineAccessRequestDTO request
    );

    DoctorPatientRecordDTO getDoctorRecord(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        DoctorPatientRecordRequestDTO request
    );

    List<PatientAllergyResponseDTO> getPatientAllergies(UUID patientId, UUID hospitalId, UUID requesterUserId);

    PatientAllergyResponseDTO createPatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        PatientAllergyRequestDTO request
    );

    PatientAllergyResponseDTO updatePatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID allergyId,
        UUID requesterUserId,
        PatientAllergyRequestDTO request
    );

    void deactivatePatientAllergy(
        UUID patientId,
        UUID hospitalId,
        UUID allergyId,
        UUID requesterUserId,
        String reason
    );

    List<PatientProblemResponseDTO> listPatientDiagnoses(UUID patientId, UUID hospitalId, boolean includeHistorical);

    PatientProblemResponseDTO createPatientDiagnosis(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        PatientDiagnosisRequestDTO request
    );

    PatientProblemResponseDTO updatePatientDiagnosis(
        UUID patientId,
        UUID hospitalId,
        UUID diagnosisId,
        UUID requesterUserId,
        PatientDiagnosisUpdateRequestDTO request
    );

    void deletePatientDiagnosis(UUID patientId, UUID hospitalId, UUID diagnosisId, UUID requesterUserId, String reason);
}
