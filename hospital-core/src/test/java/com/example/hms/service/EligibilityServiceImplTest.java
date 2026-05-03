package com.example.hms.service;

import com.example.hms.enums.EligibilityCheckType;
import com.example.hms.enums.EligibilityScheme;
import com.example.hms.enums.EligibilityStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import com.example.hms.model.insurance.EligibilityCheck;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityCheckRequestDTO;
import com.example.hms.payload.dto.insurance.EligibilityResponseDTO;
import com.example.hms.repository.EligibilityCheckRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.integration.eligibility.EligibilityProvider;
import com.example.hms.service.integration.eligibility.EligibilityProviderRequest;
import com.example.hms.service.integration.eligibility.EligibilityProviderResult;
import com.example.hms.service.integration.eligibility.StubEligibilityProvider;
import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EligibilityServiceImpl}. The service has three things
 * worth pinning: provider resolution (most-specific scheme first, fallback to
 * stub), result→entity mapping, and audit emission with FAILURE status when
 * the provider returns ERROR. The migration / DB constraints are covered by
 * Liquibase tests separately.
 */
@DisplayName("EligibilityServiceImpl")
class EligibilityServiceImplTest {

    private EligibilityCheckRepository checkRepository;
    private PatientRepository patientRepository;
    private HospitalRepository hospitalRepository;
    private PatientInsuranceRepository patientInsuranceRepository;
    private UserRepository userRepository;
    private AuditEventLogService auditService;
    private StubEligibilityProvider stubProvider;
    private IntegrationHealthRecorder healthRecorder;
    private Clock fixedClock;
    private EligibilityServiceImpl service;

    private User caller;
    private Hospital hospital;
    private Patient patient;
    private UUID userId;
    private UUID hospitalId;
    private UUID patientId;

    @BeforeEach
    void setUp() {
        checkRepository = mock(EligibilityCheckRepository.class);
        patientRepository = mock(PatientRepository.class);
        hospitalRepository = mock(HospitalRepository.class);
        patientInsuranceRepository = mock(PatientInsuranceRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditEventLogService.class);
        stubProvider = new StubEligibilityProvider();
        healthRecorder = mock(IntegrationHealthRecorder.class);
        fixedClock = Clock.fixed(Instant.parse("2026-05-01T08:30:00Z"), ZoneOffset.UTC);

        service = new EligibilityServiceImpl(
            checkRepository, patientRepository, hospitalRepository,
            patientInsuranceRepository, userRepository, auditService,
            List.of(stubProvider), fixedClock, healthRecorder
        );

        userId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        caller = new User();
        caller.setId(userId);
        caller.setUsername("dr.alice");

        hospital = Hospital.builder().name("Korle Bu Polyclinic").build();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("dr.alice", "n/a"));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private EligibilityCheckRequestDTO baseRequest(EligibilityScheme scheme,
                                                   EligibilityCheckType type,
                                                   String memberId) {
        return EligibilityCheckRequestDTO.builder()
            .patientId(patientId)
            .hospitalId(hospitalId)
            .scheme(scheme)
            .checkType(type)
            .memberId(memberId)
            .build();
    }

    private void stubLookups() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findByUsernameIgnoreCase("dr.alice")).thenReturn(Optional.of(caller));
        when(checkRepository.save(any(EligibilityCheck.class)))
            .thenAnswer(inv -> {
                EligibilityCheck saved = inv.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(UUID.randomUUID());
                }
                return saved;
            });
    }

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        @DisplayName("ELIGIBLE coverage check is persisted with stub provider's currency + validity")
        void coverageEligibleNhis() {
            stubLookups();

            EligibilityResponseDTO response = service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001"));

            assertThat(response.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(response.getResponseCode()).isEqualTo("ACTIVE");
            assertThat(response.getCopayCurrency()).isEqualTo("GHS");
            assertThat(response.getValidUntil()).isNotNull();
            assertThat(response.getRequestedByUserId()).isEqualTo(userId);

            ArgumentCaptor<EligibilityCheck> captor = ArgumentCaptor.forClass(EligibilityCheck.class);
            verify(checkRepository, times(1)).save(captor.capture());
            EligibilityCheck saved = captor.getValue();
            assertThat(saved.getRequestedAt()).isNotNull();
            assertThat(saved.getCompletedAt()).isNotNull();
            assertThat(saved.getMemberId()).isEqualTo("NHIS-001");
            assertThat(saved.getRequestedBy()).isSameAs(caller);

            verify(healthRecorder, times(1)).recordSuccess(eq("eligibility"), any());
            verify(healthRecorder, never()).recordFailure(any(), any(), any());
        }

        @Test
        @DisplayName("ERROR result emits an audit event with FAILURE status")
        void errorEmitsFailureAudit() {
            stubLookups();

            service.submit(baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, ""));

            ArgumentCaptor<AuditEventRequestDTO> auditCaptor =
                ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditService, times(1)).logEvent(auditCaptor.capture());
            AuditEventRequestDTO emitted = auditCaptor.getValue();
            assertThat(emitted.getStatus().name()).isEqualTo("FAILURE");
            assertThat(emitted.getEntityType()).isEqualTo("EligibilityCheck");
            assertThat(emitted.getEventDescription()).contains("COVERAGE")
                .contains("NHIS_GH").contains("ERROR");

            verify(healthRecorder, times(1)).recordFailure(eq("eligibility"), any(), any());
            verify(healthRecorder, never()).recordSuccess(any(), any());
        }

        @Test
        @DisplayName("missing patient throws ResourceNotFoundException; no save / audit")
        void missingPatient() {
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(patientId.toString());

            verify(checkRepository, never()).save(any());
            verify(auditService, never()).logEvent(any());
        }

        @Test
        @DisplayName("PatientInsurance belonging to a different patient is rejected")
        void wrongPatientInsurance() {
            stubLookups();
            UUID otherPatientId = UUID.randomUUID();
            Patient otherPatient = new Patient();
            otherPatient.setId(otherPatientId);
            PatientInsurance insurance = PatientInsurance.builder()
                .providerName("NHIS")
                .policyNumber("X")
                .patient(otherPatient)
                .build();
            UUID insuranceId = UUID.randomUUID();
            insurance.setId(insuranceId);
            when(patientInsuranceRepository.findById(insuranceId))
                .thenReturn(Optional.of(insurance));

            EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH,
                EligibilityCheckType.COVERAGE, "NHIS-001");
            req.setPatientInsuranceId(insuranceId);

            assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PatientInsurance does not belong");
        }

        @Test
        @DisplayName("scheme-specific provider wins over the stub fallback")
        void mostSpecificProviderWins() {
            EligibilityProvider specific = new EligibilityProvider() {
                @Override public boolean supports(EligibilityScheme scheme) {
                    return scheme == EligibilityScheme.CNAMGS_GA;
                }
                @Override public EligibilityProviderResult checkCoverage(EligibilityProviderRequest r) {
                    return EligibilityProviderResult.builder()
                        .status(EligibilityStatus.ELIGIBLE)
                        .responseCode("CNAMGS-OK")
                        .copayAmount(new BigDecimal("500.00"))
                        .copayCurrency("XAF")
                        .build();
                }
                @Override public EligibilityProviderResult requestPriorAuth(EligibilityProviderRequest r) {
                    return checkCoverage(r);
                }
            };
            service = new EligibilityServiceImpl(
                checkRepository, patientRepository, hospitalRepository,
                patientInsuranceRepository, userRepository, auditService,
                List.of(specific, stubProvider), fixedClock, healthRecorder
            );
            stubLookups();

            EligibilityResponseDTO response = service.submit(
                baseRequest(EligibilityScheme.CNAMGS_GA, EligibilityCheckType.COVERAGE, "CNAMGS-1"));

            assertThat(response.getResponseCode()).isEqualTo("CNAMGS-OK");
            assertThat(response.getCopayAmount()).isEqualByComparingTo("500.00");
        }
    }

    @Nested
    @DisplayName("findLatestForPatient")
    class FindLatest {
        @Test
        @DisplayName("delegates to repository and maps to DTO")
        void delegatesAndMaps() {
            EligibilityCheck check = EligibilityCheck.builder()
                .patient(patient).hospital(hospital)
                .scheme(EligibilityScheme.NHIS_GH)
                .checkType(EligibilityCheckType.COVERAGE)
                .status(EligibilityStatus.ELIGIBLE)
                .responseCode("ACTIVE")
                .build();
            check.setId(UUID.randomUUID());
            when(checkRepository.findFirstByPatient_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
                patientId, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE))
                .thenReturn(Optional.of(check));

            Optional<EligibilityResponseDTO> result = service.findLatestForPatient(
                patientId, /* hospitalId */ null, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE);

            assertThat(result).isPresent();
            assertThat(result.get().getResponseCode()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("delegates to the hospital-scoped repo when hospitalId is non-null")
        void hospitalScopedDelegate() {
            EligibilityCheck check = EligibilityCheck.builder()
                .patient(patient).hospital(hospital)
                .scheme(EligibilityScheme.NHIS_GH)
                .checkType(EligibilityCheckType.COVERAGE)
                .status(EligibilityStatus.ELIGIBLE)
                .responseCode("ACTIVE")
                .build();
            check.setId(UUID.randomUUID());
            when(checkRepository
                .findFirstByPatient_IdAndHospital_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
                    patientId, hospitalId, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE))
                .thenReturn(Optional.of(check));

            Optional<EligibilityResponseDTO> result = service.findLatestForPatient(
                patientId, hospitalId, EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE);

            assertThat(result).isPresent();
            verify(checkRepository, never())
                .findFirstByPatient_IdAndSchemeAndCheckTypeOrderByRequestedAtDesc(
                    any(), any(), any());
        }
    }

    @Nested
    @DisplayName("submit — guard rails")
    class SubmitGuards {

        @Test
        @DisplayName("null request throws BusinessException")
        void nullRequest() {
            assertThatThrownBy(() -> service.submit(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");
        }

        @Test
        @DisplayName("missing hospital throws ResourceNotFoundException")
        void missingHospital() {
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(hospitalId.toString());
            verify(checkRepository, never()).save(any());
        }

        @Test
        @DisplayName("missing PatientInsurance throws ResourceNotFoundException")
        void missingInsurance() {
            stubLookups();
            UUID insuranceId = UUID.randomUUID();
            when(patientInsuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());

            EligibilityCheckRequestDTO req = baseRequest(EligibilityScheme.NHIS_GH,
                EligibilityCheckType.COVERAGE, "NHIS-001");
            req.setPatientInsuranceId(insuranceId);

            assertThatThrownBy(() -> service.submit(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(insuranceId.toString());
        }

        @Test
        @DisplayName("no provider matches the requested scheme throws BusinessException")
        void noProviderMatches() {
            EligibilityProvider noneMatch = new EligibilityProvider() {
                @Override public boolean supports(EligibilityScheme scheme) { return false; }
                @Override public EligibilityProviderResult checkCoverage(EligibilityProviderRequest r) {
                    throw new IllegalStateException("must not call");
                }
                @Override public EligibilityProviderResult requestPriorAuth(EligibilityProviderRequest r) {
                    throw new IllegalStateException("must not call");
                }
            };
            service = new EligibilityServiceImpl(
                checkRepository, patientRepository, hospitalRepository,
                patientInsuranceRepository, userRepository, auditService,
                List.of(noneMatch), fixedClock, healthRecorder
            );
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));

            assertThatThrownBy(() -> service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No eligibility provider");
        }

        @Test
        @DisplayName("provider throws RuntimeException → persisted as ERROR with the message")
        void providerThrows() {
            EligibilityProvider throwing = new EligibilityProvider() {
                @Override public boolean supports(EligibilityScheme scheme) { return true; }
                @Override public EligibilityProviderResult checkCoverage(EligibilityProviderRequest r) {
                    throw new IllegalStateException("partner socket reset");
                }
                @Override public EligibilityProviderResult requestPriorAuth(EligibilityProviderRequest r) {
                    return checkCoverage(r);
                }
            };
            service = new EligibilityServiceImpl(
                checkRepository, patientRepository, hospitalRepository,
                patientInsuranceRepository, userRepository, auditService,
                List.of(throwing), fixedClock, healthRecorder
            );
            stubLookups();

            EligibilityResponseDTO out = service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001"));

            assertThat(out.getStatus()).isEqualTo(EligibilityStatus.ERROR);
            assertThat(out.getErrorMessage()).isEqualTo("partner socket reset");
        }

        @Test
        @DisplayName("PRIOR_AUTH dispatches to provider.requestPriorAuth, not checkCoverage")
        void priorAuthDispatch() {
            stubLookups();
            EligibilityCheckRequestDTO req =
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.PRIOR_AUTH, "OK-1");
            req.setServiceCode("CT-HEAD");

            EligibilityResponseDTO out = service.submit(req);

            assertThat(out.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
            assertThat(out.getPriorAuthNumber()).startsWith("PA-");
        }
    }

    @Nested
    @DisplayName("get / listByPatient")
    class Reads {
        @Test
        @DisplayName("get(id) maps the persisted check to a DTO")
        void getMaps() {
            UUID id = UUID.randomUUID();
            EligibilityCheck check = EligibilityCheck.builder()
                .patient(patient).hospital(hospital)
                .scheme(EligibilityScheme.MUTUELLE_RW)
                .checkType(EligibilityCheckType.COVERAGE)
                .status(EligibilityStatus.ELIGIBLE)
                .build();
            check.setId(id);
            when(checkRepository.findById(id)).thenReturn(Optional.of(check));

            EligibilityResponseDTO out = service.get(id);
            assertThat(out.getId()).isEqualTo(id);
            assertThat(out.getScheme()).isEqualTo(EligibilityScheme.MUTUELLE_RW);
        }

        @Test
        @DisplayName("get(id) 404s when the row is gone")
        void getNotFound() {
            UUID id = UUID.randomUUID();
            when(checkRepository.findById(id)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listByPatient pages through the repository")
        void listByPatientPasses() {
            EligibilityCheck check = EligibilityCheck.builder()
                .patient(patient).hospital(hospital)
                .scheme(EligibilityScheme.NHIS_GH)
                .checkType(EligibilityCheckType.COVERAGE)
                .status(EligibilityStatus.ELIGIBLE)
                .build();
            check.setId(UUID.randomUUID());
            org.springframework.data.domain.Page<EligibilityCheck> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(check));
            when(checkRepository.findByPatient_IdOrderByRequestedAtDesc(eq(patientId), any()))
                .thenReturn(page);

            org.springframework.data.domain.Page<EligibilityResponseDTO> out =
                service.listByPatient(patientId, /* hospitalId */ null,
                    org.springframework.data.domain.PageRequest.of(0, 20));

            assertThat(out.getContent()).hasSize(1);
            assertThat(out.getContent().get(0).getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        }

        @Test
        @DisplayName("listByPatient with hospitalId routes to the hospital-scoped repo")
        void listByPatientHospitalScoped() {
            EligibilityCheck check = EligibilityCheck.builder()
                .patient(patient).hospital(hospital)
                .scheme(EligibilityScheme.NHIS_GH)
                .checkType(EligibilityCheckType.COVERAGE)
                .status(EligibilityStatus.ELIGIBLE)
                .build();
            check.setId(UUID.randomUUID());
            org.springframework.data.domain.Page<EligibilityCheck> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(check));
            when(checkRepository.findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(
                eq(patientId), eq(hospitalId), any())).thenReturn(page);

            service.listByPatient(patientId, hospitalId,
                org.springframework.data.domain.PageRequest.of(0, 20));

            verify(checkRepository, never()).findByPatient_IdOrderByRequestedAtDesc(any(), any());
        }
    }

    @Nested
    @DisplayName("audit failure does not break the clinical flow")
    class AuditFailure {
        @Test
        @DisplayName("audit logEvent throwing is swallowed")
        void auditThrows() {
            stubLookups();
            org.mockito.Mockito.doThrow(new RuntimeException("audit pipeline down"))
                .when(auditService).logEvent(any());

            EligibilityResponseDTO out = service.submit(
                baseRequest(EligibilityScheme.NHIS_GH, EligibilityCheckType.COVERAGE, "NHIS-001"));

            assertThat(out.getStatus()).isEqualTo(EligibilityStatus.ELIGIBLE);
        }
    }
}
