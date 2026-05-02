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

    private static final Duration ACKNOWLEDGED_TTL = Duration.ofHours(24);
    private static final Duration OVERRIDDEN_TTL = Duration.ofHours(72);

    private final CdsAcknowledgementRepository repository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final ControllerAuthUtils authUtils;

    @Override
    @Transactional
    public CdsAcknowledgementResponseDTO record(Authentication auth, CdsAcknowledgementRequestDTO request) {
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

        Hospital hospital = null;
        if (request.getHospitalId() != null) {
            hospital = hospitalRepository.findById(request.getHospitalId()).orElse(null);
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
    public List<CdsAcknowledgementResponseDTO> activeForPatient(UUID patientId) {
        return repository.findActiveForPatient(patientId, LocalDateTime.now())
                .stream()
                .map(this::toDTO)
                .toList();
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
