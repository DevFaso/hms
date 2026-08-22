package com.example.hms.service.impl;

import com.example.hms.enums.TreatmentConsentMethod;
import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.enums.TreatmentConsentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientTreatmentConsent;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.TreatmentConsentRequestDTO;
import com.example.hms.payload.dto.TreatmentConsentResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientTreatmentConsentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.TreatmentConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consent-to-treat (P3 #21): revoke-never-delete, 404-not-403 tenancy, and
 * a SHA-256 digest over a canonical payload for ELECTRONIC captures (the
 * V125 tamper-evidence idiom — evidence for an audit, not an active guard).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TreatmentConsentServiceImpl implements TreatmentConsentService {

    private static final String MSG_PATIENT_NOT_FOUND = "Patient not found with ID: ";
    private static final String MSG_CONSENT_NOT_FOUND = "Consent record not found with ID: ";

    private final PatientTreatmentConsentRepository consentRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;

    @Override
    public TreatmentConsentResponseDTO record(UUID patientId, UUID hospitalId, UUID actorUserId,
                                              TreatmentConsentSource source,
                                              TreatmentConsentRequestDTO request) {
        if (hospitalId == null) {
            throw new BusinessException("An active hospital is required to record a consent.");
        }
        if (request == null || request.getMethod() == null) {
            throw new BusinessException("A consent capture method is required.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));
        if (!patient.isRegisteredInHospital(hospitalId)) {
            throw new BusinessException("Patient is not registered at this hospital.");
        }

        Appointment appointment = resolveAppointment(request.getAppointmentId(), patientId, hospitalId);
        Encounter encounter = resolveEncounter(request.getEncounterId(), patientId, hospitalId);

        LocalDateTime consentedAt = LocalDateTime.now();
        PatientTreatmentConsent consent = PatientTreatmentConsent.builder()
            .patient(patient)
            .hospital(hospital)
            .appointment(appointment)
            .encounter(encounter)
            .method(request.getMethod())
            .source(source != null ? source : TreatmentConsentSource.MANUAL)
            .signedName(trimToNull(request.getSignedName()))
            .consentedAt(consentedAt)
            .expiresAt(request.getExpiresAt())
            .notes(trimToNull(request.getNotes()))
            .build();

        if (request.getMethod() == TreatmentConsentMethod.ELECTRONIC) {
            consent.setSignatureHash(sha256(canonicalPayload(patientId, hospitalId,
                consent.getSignedName(), consentedAt)));
        }
        if (actorUserId != null) {
            userRepository.findById(actorUserId).ifPresent(consent::setRecordedBy);
            staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)
                .ifPresent(consent::setRecordedByStaff);
        }
        return toDto(consentRepository.save(consent));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TreatmentConsentResponseDTO> getForPatient(UUID patientId, UUID hospitalId) {
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId));
        // 404-not-403: a scoped caller learns nothing about unregistered patients.
        if (hospitalId != null && !patient.isRegisteredInHospital(hospitalId)) {
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND + patientId);
        }
        return consentRepository.findForPatient(patientId, hospitalId).stream()
            .map(this::toDto)
            .toList();
    }

    @Override
    public TreatmentConsentResponseDTO revoke(UUID consentId, UUID hospitalId, UUID actorUserId,
                                              String reason) {
        PatientTreatmentConsent consent = consentRepository.findById(consentId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_CONSENT_NOT_FOUND + consentId));
        if (hospitalId != null
            && (consent.getHospital() == null
                || !Objects.equals(consent.getHospital().getId(), hospitalId))) {
            throw new ResourceNotFoundException(MSG_CONSENT_NOT_FOUND + consentId);
        }
        if (consent.getStatus() == TreatmentConsentStatus.REVOKED) {
            throw new BusinessException("This consent has already been revoked.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A revocation reason is required — the patient withdrew "
                + "consent for a reason worth recording.");
        }
        consent.setStatus(TreatmentConsentStatus.REVOKED);
        consent.setRevokedAt(LocalDateTime.now());
        consent.setRevokedByUserId(actorUserId);
        consent.setRevocationReason(reason.trim());
        return toDto(consentRepository.save(consent));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveConsent(UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null) {
            return false;
        }
        return consentRepository.existsByPatient_IdAndHospital_IdAndStatus(
            patientId, hospitalId, TreatmentConsentStatus.ACTIVE);
    }

    /* ── Guards ────────────────────────────────────────────────────────── */

    private Appointment resolveAppointment(UUID appointmentId, UUID patientId, UUID hospitalId) {
        if (appointmentId == null) {
            return null;
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Appointment not found with ID: " + appointmentId));
        if (appointment.getPatient() == null
            || !Objects.equals(appointment.getPatient().getId(), patientId)) {
            throw new BusinessException("The appointment belongs to a different patient.");
        }
        if (appointment.getHospital() != null
            && !Objects.equals(appointment.getHospital().getId(), hospitalId)) {
            throw new BusinessException("The appointment belongs to a different hospital.");
        }
        return appointment;
    }

    private Encounter resolveEncounter(UUID encounterId, UUID patientId, UUID hospitalId) {
        if (encounterId == null) {
            return null;
        }
        Encounter encounter = encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Encounter not found with ID: " + encounterId));
        if (encounter.getPatient() == null
            || !Objects.equals(encounter.getPatient().getId(), patientId)) {
            throw new BusinessException("The encounter belongs to a different patient.");
        }
        if (encounter.getHospital() != null
            && !Objects.equals(encounter.getHospital().getId(), hospitalId)) {
            throw new BusinessException("The encounter belongs to a different hospital.");
        }
        return encounter;
    }

    /* ── Digest ────────────────────────────────────────────────────────── */

    private String canonicalPayload(UUID patientId, UUID hospitalId, String signedName,
                                    LocalDateTime consentedAt) {
        return String.join("|",
            String.valueOf(patientId),
            String.valueOf(hospitalId),
            String.valueOf(signedName),
            String.valueOf(consentedAt));
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /* ── Mapping ───────────────────────────────────────────────────────── */

    private TreatmentConsentResponseDTO toDto(PatientTreatmentConsent consent) {
        return TreatmentConsentResponseDTO.builder()
            .id(consent.getId())
            .patientId(consent.getPatient() != null ? consent.getPatient().getId() : null)
            .hospitalId(consent.getHospital() != null ? consent.getHospital().getId() : null)
            .hospitalName(consent.getHospital() != null ? consent.getHospital().getName() : null)
            .appointmentId(consent.getAppointment() != null ? consent.getAppointment().getId() : null)
            .encounterId(consent.getEncounter() != null ? consent.getEncounter().getId() : null)
            .status(consent.getStatus())
            .method(consent.getMethod())
            .source(consent.getSource())
            .signedName(consent.getSignedName())
            .signatureHash(consent.getSignatureHash())
            .consentedAt(consent.getConsentedAt())
            .expiresAt(consent.getExpiresAt())
            .recordedByName(resolveStaffName(consent.getRecordedByStaff()))
            .revokedAt(consent.getRevokedAt())
            .revocationReason(consent.getRevocationReason())
            .notes(consent.getNotes())
            .createdAt(consent.getCreatedAt())
            .build();
    }

    private String resolveStaffName(Staff staff) {
        return Optional.ofNullable(staff)
            .map(s -> {
                if (s.getUser() == null) {
                    return s.getName();
                }
                String first = s.getUser().getFirstName();
                String last = s.getUser().getLastName();
                String combined =
                    ((first != null ? first.trim() : "") + " " + (last != null ? last.trim() : "")).trim();
                return combined.isEmpty() ? s.getName() : combined;
            })
            .orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
