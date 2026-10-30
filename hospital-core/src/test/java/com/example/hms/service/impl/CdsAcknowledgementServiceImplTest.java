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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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

    /**
     * A fixed clock the day before a daylight-saving change (America/New_York
     * falls back at 02:00 on 2026-11-01): noon EDT on 2026-10-31. The TTL
     * tests are then the same on every machine, whatever its zone or date,
     * and they exercise the DST case instead of only passing outside it.
     */
    private static final ZoneId DST_ZONE = ZoneId.of("America/New_York");
    private static final Instant NOW = LocalDateTime.of(2026, 10, 31, 12, 0).atZone(DST_ZONE).toInstant();
    private final Clock clock = Clock.fixed(NOW, DST_ZONE);

    private CdsAcknowledgementServiceImpl service;

    private UUID patientId;
    private UUID userId;
    private UUID hospitalId;
    private Patient patient;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CdsAcknowledgementServiceImpl(repository, patientRepository, hospitalRepository,
                userRepository, authUtils, clock);
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        user = new User();
        user.setId(userId);
        user.setUsername("dr.alice");
    }

    private CdsAcknowledgementRequestDTO buildRequest(CdsAcknowledgementAction action, String reason) {
        return CdsAcknowledgementRequestDTO.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .cardSummary("Sepsis qSOFA ≥ 2")
                .indicator("critical")
                .action(action)
                .reason(reason)
                .cardUuid("card-1")
                .build();
    }

    private void stubInScopeUser() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authUtils.resolveHospitalScope(auth, hospitalId, false)).thenReturn(hospitalId);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
    }

    @Test
    @DisplayName("ACKNOWLEDGED is recorded with a 24-hour TTL")
    void acknowledged_uses24hTtl() {
        stubInScopeUser();
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        CdsAcknowledgementResponseDTO result =
                service.acknowledge(auth, buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null));

        ArgumentCaptor<CdsAcknowledgement> captor = ArgumentCaptor.forClass(CdsAcknowledgement.class);
        verify(repository).save(captor.capture());
        CdsAcknowledgement saved = captor.getValue();

        // Declared assertion change (round-18 merge): was "ElapsedTime from the machine's now, 23 to 24 whole
        // hours", which read 25 across a DST change. Now exactly 24 real hours on a fixed clock that crosses one.
        assertThat(Duration.between(NOW, saved.getExpiresAt().atZone(DST_ZONE).toInstant()))
                .isEqualTo(Duration.ofHours(24));
        assertThat(saved.getExpiresAt()).as("noon EDT + 24 real hours = 11:00 EST")
                .isEqualTo(LocalDateTime.of(2026, 11, 1, 11, 0));
        assertThat(result.getAction()).isEqualTo(CdsAcknowledgementAction.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("the hospital named in the body narrows the scope before the tenant-scoped patient load reads it")
    void resolvesTheScopeBeforeTheScopedPatientLoad() {
        stubInScopeUser();
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        service.acknowledge(auth, buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null));

        // The load seals the scope; a narrow after it would be refused.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(authUtils, patientRepository);
        order.verify(authUtils).resolveHospitalScope(auth, hospitalId, false);
        order.verify(patientRepository).findById(patientId);
    }

    @Test
    @DisplayName("a provider scope is refused (not found), never saved as a hospital-less, global acknowledgement")
    void providerScopeIsRefused() {
        stubInScopeUser();
        // What findClinicalById answers for a pharmacy or laboratory id.
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.empty());

        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null);

        assertThatThrownBy(() -> service.acknowledge(auth, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("OVERRIDDEN requires a reason")
    void overridden_requiresReason() {
        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.OVERRIDDEN, "  ");

        assertThatThrownBy(() -> service.acknowledge(auth, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reason");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("OVERRIDDEN gets a 72-hour TTL")
    void overridden_uses72hTtl() {
        stubInScopeUser();
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        CdsAcknowledgementRequestDTO req =
                buildRequest(CdsAcknowledgementAction.OVERRIDDEN, "Reviewed history; safe to proceed");
        service.acknowledge(auth, req);

        ArgumentCaptor<CdsAcknowledgement> captor = ArgumentCaptor.forClass(CdsAcknowledgement.class);
        verify(repository).save(captor.capture());
        // Declared assertion change (round-18 merge): was "ElapsedTime from the machine's now, 71 to 72 whole
        // hours", which read 73 across the 2026-11-01 DST change. Now exactly 72 real hours on a fixed clock.
        LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        assertThat(Duration.between(NOW, expiresAt.atZone(DST_ZONE).toInstant())).isEqualTo(Duration.ofHours(72));
        assertThat(expiresAt).as("noon EDT + 72 real hours = 11:00 EST").isEqualTo(LocalDateTime.of(2026, 11, 3, 11, 0));
    }

    @Test
    @DisplayName("404 when patient is not found")
    void recordsRejectsMissingPatient() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null);

        assertThatThrownBy(() -> service.acknowledge(auth, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("403 when caller's resolved hospital does not match patient's hospital")
    void rejectsCrossHospitalAccess() {
        UUID otherHospitalId = UUID.randomUUID();
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authUtils.resolveHospitalScope(auth, hospitalId, false)).thenReturn(otherHospitalId);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);

        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null);
        assertThatThrownBy(() -> service.acknowledge(auth, request))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN bypasses the hospital-scope check")
    void superAdminMayAcknowledgeAnyPatient() {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authUtils.resolveHospitalScope(auth, hospitalId, false)).thenReturn(null);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);
        when(repository.save(any(CdsAcknowledgement.class))).thenAnswer(inv -> {
            CdsAcknowledgement saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        CdsAcknowledgementRequestDTO request = buildRequest(CdsAcknowledgementAction.ACKNOWLEDGED, null);
        assertThat(service.acknowledge(auth, request)).isNotNull();
        verify(repository).save(any(CdsAcknowledgement.class));
    }

    @Test
    @DisplayName("activeForPatient enforces patient access then delegates to repository")
    void active_delegates() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(hospitalId);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);
        when(repository.findActiveForPatient(any(UUID.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.activeForPatient(auth, patientId)).isEmpty();
        verify(repository).findActiveForPatient(any(UUID.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("activeForPatient rejects callers outside the patient's hospital scope")
    void active_rejectsCrossHospital() {
        UUID otherHospitalId = UUID.randomUUID();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(authUtils.resolveHospitalScope(auth, null, false)).thenReturn(otherHospitalId);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> service.activeForPatient(auth, patientId))
                .isInstanceOf(AccessDeniedException.class);
    }
}
