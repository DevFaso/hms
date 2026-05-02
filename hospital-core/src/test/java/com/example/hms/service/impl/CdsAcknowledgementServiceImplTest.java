package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.CdsAcknowledgementAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.CdsAcknowledgement;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.cds.CdsAcknowledgementRequestDTO;
import com.example.hms.payload.dto.cds.CdsAcknowledgementResponseDTO;
import com.example.hms.repository.CdsAcknowledgementRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CdsAcknowledgementServiceImpl")
class CdsAcknowledgementServiceImplTest {

    @Mock private CdsAcknowledgementRepository repository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private Authentication auth;

    @InjectMocks private CdsAcknowledgementServiceImpl service;

    private UUID patientId;
    private UUID userId;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        user = new User();
        user.setId(userId);
        user.setUsername("dr.alice");
    }

    private CdsAcknowledgementRequestDTO buildRequest(CdsAcknowledgementAction action, String reason) {
        return CdsAcknowledgementRequestDTO.builder()
                .patientId(patientId)
                .cardSummary("Sepsis qSOFA ≥ 2")
                .indicator("critical")
                .action(action)
                .reason(reason)
                .cardUuid("card-1")
                .build();
    }

    @Test
    @DisplayName("ACKNOWLEDGED is recorded with a 24-hour TTL")
    void acknowledged_uses24hTtl() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        CdsAcknowledgementResponseDTO result =
                service.record(auth, buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null));

        ArgumentCaptor<CdsAcknowledgement> captor = ArgumentCaptor.forClass(CdsAcknowledgement.class);
        verify(repository).save(captor.capture());
        CdsAcknowledgement saved = captor.getValue();

        long hoursTillExpiry = java.time.Duration.between(LocalDateTime.now(), saved.getExpiresAt()).toHours();
        assertThat(hoursTillExpiry).isBetween(23L, 24L);
        assertThat(result.getAction()).isEqualTo(CdsAcknowledgementAction.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("OVERRIDDEN requires a reason")
    void overridden_requiresReason() {
        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.OVERRIDDEN, "  ");

        assertThatThrownBy(() -> service.record(auth, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("OVERRIDDEN gets a 72-hour TTL")
    void overridden_uses72hTtl() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        service.record(auth, buildRequest(CdsAcknowledgementAction.OVERRIDDEN, "Reviewed history; safe to proceed"));

        ArgumentCaptor<CdsAcknowledgement> captor = ArgumentCaptor.forClass(CdsAcknowledgement.class);
        verify(repository).save(captor.capture());
        long hoursTillExpiry = java.time.Duration.between(LocalDateTime.now(), captor.getValue().getExpiresAt()).toHours();
        assertThat(hoursTillExpiry).isBetween(71L, 72L);
    }

    @Test
    @DisplayName("404 when patient is not found")
    void recordsRejectsMissingPatient() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null);

        assertThatThrownBy(() -> service.record(auth, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("activeForPatient delegates to repository with current time")
    void active_delegates() {
        when(repository.findActiveForPatient(any(UUID.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.activeForPatient(patientId)).isEmpty();
        verify(repository).findActiveForPatient(any(UUID.class), any(LocalDateTime.class));
    }
}
