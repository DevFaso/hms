package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.mapper.PatientConsentMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatientConsentServiceImpl implements PatientConsentService {
    private static final String TO_HOSPITAL_SEPARATOR = " to hospital ";
    private static final String PATIENT_CONSENT_TYPE = "PATIENT_CONSENT";
    private static final String AUDIT_LOG_FAILURE_MSG = "❌ Failed to save audit log: {}";


    private final PatientConsentRepository consentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientConsentMapper consentMapper;
    private final PatientMapper patientMapper;
    private final HospitalMapper hospitalMapper;
    private final AuditEventLogRepository auditRepository;

    @Override
    @Transactional
    public PatientConsentResponseDTO grantConsent(PatientConsentRequestDTO requestDTO) {
        UUID patientId = requestDTO.getPatientId();
        UUID fromHospitalId = requestDTO.getFromHospitalId();
        UUID toHospitalId = requestDTO.getToHospitalId();

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));

        Hospital fromHospital = hospitalRepository.findById(fromHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("From Hospital not found."));

        Hospital toHospital = hospitalRepository.findById(toHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("To Hospital not found."));

        Hibernate.initialize(patient.getHospitalRegistrations());

        boolean isRegistered = registrationRepository.isPatientRegisteredInHospitalFixed(patientId, fromHospitalId);
        log.debug("[DEBUG] isPatientRegisteredInHospitalFixed(patientId={}, hospitalId={}) => {}", patientId, fromHospitalId, isRegistered);

        if (!isRegistered) {
            log.error(">>> No active registration found for patient '{}' in hospital '{}'", patientId, fromHospitalId);
            throw new IllegalStateException("Patient is not registered in the specified hospital.");
        }

        Hibernate.initialize(patient.getHospitalRegistrations());

        PatientConsent consent = consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .orElseGet(() -> consentMapper.toEntity(requestDTO, patient, fromHospital, toHospital));

        consent.setConsentGiven(true);
        consent.setConsentExpiration(requestDTO.getConsentExpiration());
        consent.setPurpose(requestDTO.getPurpose());

        PatientConsent savedConsent = consentRepository.save(consent);

        try {
            auditRepository.save(AuditEventLog.builder()
                .user(patient.getUser())
                .eventType(AuditEventType.CONSENT_GRANTED)
                .eventDescription("Consent granted from hospital " + fromHospitalId + TO_HOSPITAL_SEPARATOR + toHospitalId)
                .resourceId(patient.getId().toString())
                .entityType(PATIENT_CONSENT_TYPE)
                .status(AuditStatus.SUCCESS)
                .details("Purpose: " + requestDTO.getPurpose())
                .build());
        } catch (RuntimeException e) {
            log.error(AUDIT_LOG_FAILURE_MSG, e.getMessage(), e);
        }

        return mapWithDetails(savedConsent);
    }

    @Override
    public void revokeConsent(UUID patientId, UUID fromHospitalId, UUID toHospitalId) {
        PatientConsent consent = consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Consent not found."));

        consent.setConsentGiven(false);
        consentRepository.save(consent);

        try {
            auditRepository.save(AuditEventLog.builder()
                .user(consent.getPatient().getUser())
                .eventType(AuditEventType.CONSENT_REVOKED)
                .eventDescription("Consent revoked from hospital " + fromHospitalId + TO_HOSPITAL_SEPARATOR + toHospitalId)
                .resourceId(patientId.toString())
                .entityType(PATIENT_CONSENT_TYPE)
                .status(AuditStatus.SUCCESS)
                .build());
        } catch (RuntimeException e) {
            log.error(AUDIT_LOG_FAILURE_MSG, e.getMessage(), e);
        }
    }

    @Override
    public Page<PatientConsentResponseDTO> getAllConsents(Pageable pageable) {
        return consentRepository.findAll(pageable).map(this::mapWithDetails);
    }

    @Override
    public Page<PatientConsentResponseDTO> getConsentsByPatient(UUID patientId, Pageable pageable) {
        return consentRepository.findAllByPatientId(patientId, pageable).map(this::mapWithDetails);
    }

    @Override
    public Page<PatientConsentResponseDTO> getConsentsByFromHospital(UUID fromHospitalId, Pageable pageable) {
        return consentRepository.findAllByFromHospitalId(fromHospitalId, pageable).map(this::mapWithDetails);
    }

    @Override
    public Page<PatientConsentResponseDTO> getConsentsByToHospital(UUID toHospitalId, Pageable pageable) {
        return consentRepository.findAllByToHospitalId(toHospitalId, pageable).map(this::mapWithDetails);
    }

    @Override
    public boolean isConsentActive(UUID patientId, UUID fromHospitalId, UUID toHospitalId) {
        return consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .map(PatientConsent::isConsentActive)
            .orElse(false);
    }

    @Override
    @Transactional
    public PatientConsentResponseDTO grantConsentWithDetails(
        PatientConsentRequestDTO requestDTO,
        PatientResponseDTO patientDTO,
        HospitalResponseDTO fromHospitalDTO,
        HospitalResponseDTO toHospitalDTO
    ) {
        UUID patientId = patientDTO.getId();
        UUID fromHospitalId = fromHospitalDTO.getId();
        UUID toHospitalId = toHospitalDTO.getId();

        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));

        Hospital fromHospital = hospitalRepository.findById(fromHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("From Hospital not found with ID: " + fromHospitalId));

        Hospital toHospital = hospitalRepository.findById(toHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("To Hospital not found with ID: " + toHospitalId));

        Hibernate.initialize(patient.getHospitalRegistrations());

        PatientConsent consent = consentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospitalId, toHospitalId)
            .orElseGet(() -> consentMapper.toEntity(requestDTO, patient, fromHospital, toHospital));

        consent.setConsentGiven(true);
        consent.setConsentExpiration(requestDTO.getConsentExpiration());
        consent.setPurpose(requestDTO.getPurpose());

        PatientConsent savedConsent = consentRepository.save(consent);

        try {
            auditRepository.save(AuditEventLog.builder()
                .user(patient.getUser())
                .eventType(AuditEventType.CONSENT_GRANTED)
                .eventDescription("Consent granted (with details) from hospital " + fromHospitalId + TO_HOSPITAL_SEPARATOR + toHospitalId)
                .resourceId(patient.getId().toString())
                .entityType(PATIENT_CONSENT_TYPE)
                .status(AuditStatus.SUCCESS)
                .details("Purpose: " + requestDTO.getPurpose())
                .build());
        } catch (RuntimeException e) {
            log.error(AUDIT_LOG_FAILURE_MSG, e.getMessage(), e);
        }

        return consentMapper.toDto(savedConsent, patientDTO, fromHospitalDTO, toHospitalDTO);
    }

    private PatientConsentResponseDTO mapWithDetails(PatientConsent consent) {
        PatientResponseDTO patientDTO = patientMapper.toPatientDTO(
            consent.getPatient(),
            consent.getFromHospital().getId(),
            true,
            true
        );
        HospitalResponseDTO fromHospitalDTO = hospitalMapper.toHospitalDTO(consent.getFromHospital());
        HospitalResponseDTO toHospitalDTO = hospitalMapper.toHospitalDTO(consent.getToHospital());
        return consentMapper.toDto(consent, patientDTO, fromHospitalDTO, toHospitalDTO);
    }
}
