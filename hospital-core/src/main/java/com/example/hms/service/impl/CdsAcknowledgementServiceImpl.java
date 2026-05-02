package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.CdsAcknowledgementAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.CdsAcknowledgement;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.cds.CdsAcknowledgementRequestDTO;
import com.example.hms.payload.dto.cds.CdsAcknowledgementResponseDTO;
import com.example.hms.repository.CdsAcknowledgementRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.CdsAcknowledgementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdsAcknowledgementServiceImpl implements CdsAcknowledgementService {

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final Duration ACKNOWLEDGED_TTL = Duration.ofHours(24);
    private static final Duration OVERRIDDEN_TTL = Duration.ofHours(72);

    private final CdsAcknowledgementRepository repository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final ControllerAuthUtils authUtils;

    @Override
    @Transactional
    public CdsAcknowledgementResponseDTO acknowledge(Authentication auth,
                                                     CdsAcknowledgementRequestDTO request) {
        authUtils.requireAuth(auth);

        if (request.getAction() == CdsAcknowledgementAction.OVERRIDDEN
                && (request.getReason() == null || request.getReason().isBlank())) {
            throw new BusinessException("A reason is required when overriding a critical advisory.");
        }

        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to resolve user from authentication."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        UUID resolvedHospitalId = authUtils.resolveHospitalScope(
                auth, request.getHospitalId(), false);
        requirePatientAccessible(auth, patient, resolvedHospitalId);

        Hospital hospital = null;
        if (resolvedHospitalId != null) {
            hospital = hospitalRepository.findById(resolvedHospitalId).orElse(null);
        }

        Duration ttl = request.getAction() == CdsAcknowledgementAction.OVERRIDDEN
                ? OVERRIDDEN_TTL
                : ACKNOWLEDGED_TTL;

        CdsAcknowledgement entity = CdsAcknowledgement.builder()
                .patient(patient)
                .hospital(hospital)
                .user(user)
                .cardUuid(request.getCardUuid())
                .cardSummary(request.getCardSummary())
                .indicator(request.getIndicator())
                .action(request.getAction())
                .reason(request.getReason() != null ? request.getReason().trim() : null)
                .expiresAt(LocalDateTime.now().plus(ttl))
                .build();

        entity = repository.save(entity);
        log.info("CDS advisory {} by user {} on patient {} (cardUuid={}, indicator={})",
                request.getAction(), userId, patient.getId(),
                request.getCardUuid(), request.getIndicator());
        return toDTO(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CdsAcknowledgementResponseDTO> activeForPatient(Authentication auth, UUID patientId) {
        authUtils.requireAuth(auth);
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, null, false);
        requirePatientAccessible(auth, patient, resolvedHospitalId);

        return repository.findActiveForPatient(patientId, LocalDateTime.now())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Reject access when the patient's hospital is outside the caller's scope.
     * SUPER_ADMIN bypasses the check; for everyone else the resolved hospital
     * (from the JWT / active assignment) must match the patient's hospital.
     */
    private void requirePatientAccessible(Authentication auth, Patient patient, UUID resolvedHospitalId) {
        if (authUtils.hasAuthority(auth, ROLE_SUPER_ADMIN)) {
            return;
        }
        UUID patientHospitalId = patient.getHospitalId();
        if (patientHospitalId != null && resolvedHospitalId != null
                && patientHospitalId.equals(resolvedHospitalId)) {
            return;
        }
        throw new AccessDeniedException(
            "You do not have access to this patient's chart in the resolved hospital scope.");
    }

    private CdsAcknowledgementResponseDTO toDTO(CdsAcknowledgement a) {
        return CdsAcknowledgementResponseDTO.builder()
                .id(a.getId())
                .patientId(a.getPatient() != null ? a.getPatient().getId() : null)
                .hospitalId(a.getHospital() != null ? a.getHospital().getId() : null)
                .cardUuid(a.getCardUuid())
                .cardSummary(a.getCardSummary())
                .indicator(a.getIndicator())
                .action(a.getAction())
                .reason(a.getReason())
                .createdAt(a.getCreatedAt())
                .expiresAt(a.getExpiresAt())
                .build();
    }
}
