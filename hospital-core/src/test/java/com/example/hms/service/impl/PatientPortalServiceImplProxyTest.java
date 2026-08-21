package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proxy / family-access tests: permission-vocabulary normalization
 * (legacy un-prefixed tokens vs canonical VIEW_*), expiry enforcement,
 * and canonical storage on grant.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class PatientPortalServiceImplProxyTest {

    @Mock private PatientRepository patientRepository;
    @Mock private ControllerAuthUtils authUtils;
    @Mock private PatientProxyRepository patientProxyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AppointmentService appointmentService;

    @InjectMocks
    private PatientPortalServiceImpl service;

    @Mock private Authentication auth;

    private UUID proxyUserId;
    private UUID grantorPatientId;
    private Patient grantor;
    private User proxyUser;

    @BeforeEach
    void setUp() {
        proxyUserId = UUID.randomUUID();
        grantorPatientId = UUID.randomUUID();

        proxyUser = new User();
        proxyUser.setId(proxyUserId);
        proxyUser.setUsername("caregiver.sam");
        proxyUser.setFirstName("Sam");
        proxyUser.setLastName("Kabore");

        grantor = new Patient();
        grantor.setId(grantorPatientId);
        grantor.setFirstName("Awa");
        grantor.setLastName("Diallo");
    }

    private PatientProxy buildGrant(String permissions, LocalDateTime expiresAt) {
        PatientProxy grant = PatientProxy.builder()
                .grantorPatient(grantor)
                .proxyUser(proxyUser)
                .relationship(ProxyRelationship.CAREGIVER)
                .status(ProxyStatus.ACTIVE)
                .permissions(permissions)
                .expiresAt(expiresAt)
                .build();
        grant.setId(UUID.randomUUID());
        return grant;
    }

    private void stubActiveGrant(PatientProxy grant) {
        when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(proxyUserId));
        when(patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                grantorPatientId, proxyUserId, ProxyStatus.ACTIVE))
                .thenReturn(Optional.ofNullable(grant));
    }

    @Nested
    @DisplayName("verifyProxyAccess via getProxyAppointments")
    class VerifyProxyAccess {

        @Test
        @DisplayName("canonical VIEW_* scope grants access")
        void canonicalScope_allowed() {
            stubActiveGrant(buildGrant("VIEW_APPOINTMENTS", null));
            when(patientRepository.findById(grantorPatientId)).thenReturn(Optional.of(grantor));
            when(appointmentService.getAppointmentsForVerifiedPatient(grantorPatientId))
                    .thenReturn(List.of(AppointmentResponseDTO.builder().id(UUID.randomUUID()).build()));

            List<AppointmentResponseDTO> result =
                    service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("legacy un-prefixed scope is normalized and grants access")
        void legacyScope_normalized() {
            stubActiveGrant(buildGrant("APPOINTMENTS,LAB_RESULTS", null));
            when(patientRepository.findById(grantorPatientId)).thenReturn(Optional.of(grantor));
            when(appointmentService.getAppointmentsForVerifiedPatient(grantorPatientId))
                    .thenReturn(List.of());

            assertThat(service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH)).isEmpty();
        }

        @Test
        @DisplayName("ALL scope grants every domain")
        void allScope_allowed() {
            stubActiveGrant(buildGrant("ALL", null));
            when(patientRepository.findById(grantorPatientId)).thenReturn(Optional.of(grantor));
            when(appointmentService.getAppointmentsForVerifiedPatient(grantorPatientId))
                    .thenReturn(List.of());

            assertThat(service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH)).isEmpty();
        }

        @Test
        @DisplayName("scope for a different domain is denied")
        void wrongScope_denied() {
            stubActiveGrant(buildGrant("VIEW_BILLING", null));

            assertThatThrownBy(() -> service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("does not include");
        }

        @Test
        @DisplayName("expired grant is denied even while status is still ACTIVE")
        void expiredGrant_denied() {
            stubActiveGrant(buildGrant("ALL", LocalDateTime.now().minusDays(1)));

            assertThatThrownBy(() -> service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("expired");

            verify(appointmentService, never()).getAppointmentsForVerifiedPatient(any());
        }

        @Test
        @DisplayName("future expiry still grants access")
        void futureExpiry_allowed() {
            stubActiveGrant(buildGrant("ALL", LocalDateTime.now().plusDays(30)));
            when(patientRepository.findById(grantorPatientId)).thenReturn(Optional.of(grantor));
            when(appointmentService.getAppointmentsForVerifiedPatient(grantorPatientId))
                    .thenReturn(List.of());

            assertThat(service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH)).isEmpty();
        }

        @Test
        @DisplayName("no grant at all is denied")
        void noGrant_denied() {
            stubActiveGrant(null);

            assertThatThrownBy(() -> service.getProxyAppointments(auth, grantorPatientId, Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("do not have proxy access");
        }
    }

    @Nested
    @DisplayName("proxy listings")
    class ProxyListings {

        @Test
        @DisplayName("getMyProxyAccess filters out expired grants")
        void proxyAccess_filtersExpired() {
            PatientProxy live = buildGrant("ALL", LocalDateTime.now().plusDays(5));
            PatientProxy expired = buildGrant("ALL", LocalDateTime.now().minusDays(5));

            when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(proxyUserId));
            when(patientProxyRepository.findByProxyUser_IdAndStatus(proxyUserId, ProxyStatus.ACTIVE))
                    .thenReturn(List.of(live, expired));

            List<ProxyResponseDTO> result = service.getMyProxyAccess(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(live.getId());
        }

        @Test
        @DisplayName("getMyProxies shows expired-but-ACTIVE rows with effective status EXPIRED")
        void myProxies_effectiveExpiredStatus() {
            grantor.setUser(new User());
            PatientProxy expired = buildGrant("ALL", LocalDateTime.now().minusDays(5));

            when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(proxyUserId));
            when(patientRepository.findByUserId(proxyUserId)).thenReturn(Optional.of(grantor));
            when(patientProxyRepository.findByGrantorPatient_IdAndStatus(grantorPatientId, ProxyStatus.ACTIVE))
                    .thenReturn(List.of(expired));

            List<ProxyResponseDTO> result = service.getMyProxies(auth);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(ProxyStatus.EXPIRED);
        }
    }

    @Nested
    @DisplayName("grantProxy canonical storage")
    class GrantProxyCanonical {

        @Test
        @DisplayName("legacy vocabulary is stored in canonical VIEW_* form")
        void legacyGrant_storedCanonical() {
            User grantorUser = new User();
            grantorUser.setId(UUID.randomUUID());
            grantor.setUser(grantorUser);

            when(authUtils.resolveUserId(auth)).thenReturn(Optional.of(grantorUser.getId()));
            when(patientRepository.findByUserId(grantorUser.getId())).thenReturn(Optional.of(grantor));
            when(userRepository.findByUsername("caregiver.sam")).thenReturn(Optional.of(proxyUser));
            when(patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                    grantorPatientId, proxyUserId, ProxyStatus.ACTIVE)).thenReturn(Optional.empty());
            when(patientProxyRepository.save(any(PatientProxy.class))).thenAnswer(inv -> inv.getArgument(0));

            ProxyGrantRequestDTO dto = ProxyGrantRequestDTO.builder()
                    .proxyUsername("caregiver.sam")
                    .relationship(ProxyRelationship.CAREGIVER)
                    .permissions("appointments, Medications")
                    .build();

            service.grantProxy(auth, dto);

            ArgumentCaptor<PatientProxy> captor = ArgumentCaptor.forClass(PatientProxy.class);
            verify(patientProxyRepository).save(captor.capture());
            assertThat(captor.getValue().getPermissions()).isEqualTo("VIEW_APPOINTMENTS,VIEW_MEDICATIONS");
        }
    }
}
