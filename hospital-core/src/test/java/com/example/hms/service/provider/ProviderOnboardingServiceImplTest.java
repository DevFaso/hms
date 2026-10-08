package com.example.hms.service.provider;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.provider.ProviderAddressDTO;
import com.example.hms.payload.dto.provider.ProviderBusinessIdentityDTO;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderProfessionalDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-1 to AC-3 and rule 2, one decision at a time: creation with
 * both evidence layers (SUSPENDED, inactive, no expiry date needed), the
 * RCCM/IFU/CNSS consistency check, the duplicate-business 409s, reject,
 * resubmit and revoke, and audit rows that carry no business number or name.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderOnboardingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

    @Mock private HospitalRepository hospitalRepository;
    @Mock private ProviderVerificationRepository verificationRepository;
    @Mock private HospitalLifecycleStatusService lifecycleStatusService;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private RoleValidator roleValidator;

    private ProviderOnboardingServiceImpl service;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProviderOnboardingServiceImpl(hospitalRepository, verificationRepository,
            lifecycleStatusService, auditEventLogService, roleValidator, Clock.fixed(NOW, ZoneOffset.UTC));
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        when(hospitalRepository.save(any())).thenAnswer(inv -> {
            Hospital h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId(UUID.randomUUID());
            }
            return h;
        });
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(verificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(actorId).superAdmin(true).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Nested
    @DisplayName("create (AC-1)")
    class Create {

        @Test
        @DisplayName("a SUSPENDED, inactive facility of the type, and a SUBMITTED verification with both layers")
        void createsSuspended() {
            ProviderResponseDTO response = service.create(createRequest(FacilityType.PHARMACY));

            ArgumentCaptor<Hospital> facility = ArgumentCaptor.forClass(Hospital.class);
            verify(hospitalRepository).save(facility.capture());
            assertThat(facility.getValue().getFacilityType()).isEqualTo(FacilityType.PHARMACY);
            assertThat(facility.getValue().isActive()).isFalse();
            assertThat(facility.getValue().getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
            assertThat(facility.getValue().getCode()).isEqualTo("PH-OUAGA-1");
            assertThat(facility.getValue().getName()).isEqualTo("Pharmacie du Marché");

            ArgumentCaptor<ProviderVerification> verification = ArgumentCaptor.forClass(ProviderVerification.class);
            verify(verificationRepository).save(verification.capture());
            ProviderVerification v = verification.getValue();
            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            assertThat(v.getRccmNumber()).isEqualTo("BF-OUA-2019-B-1234");
            assertThat(v.getIfuNumber()).isEqualTo("00012345X");
            assertThat(v.getLicenceNumber()).isEqualTo("LIC-77");
            assertThat(v.getResponsibleProfessionalRegistration()).isEqualTo("ONP-123");
            assertThat(v.isIfuMatchesRccm()).isFalse();
            assertThat(v.isCnssMatchesRccm()).isFalse();

            assertThat(response.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            verify(lifecycleStatusService).invalidate();
        }

        @Test
        @DisplayName("no expiry date is required anywhere")
        void noExpiryNeeded() {
            ProviderCreateRequestDTO request = createRequest(FacilityType.LABORATORY);
            request.getProfessional().setLicenceIssuedOn(null);
            request.getProfessional().setLicenceExpiresOn(null);

            service.create(request);

            ArgumentCaptor<ProviderVerification> verification = ArgumentCaptor.forClass(ProviderVerification.class);
            verify(verificationRepository).save(verification.capture());
            assertThat(verification.getValue().getLicenceExpiresOn()).isNull();
        }

        @Test
        @DisplayName("the licensing authority is held in one spelling, like the number")
        void authorityIsNormalised() {
            ProviderCreateRequestDTO request = createRequest(FacilityType.PHARMACY);
            request.getProfessional().setLicenceAuthority("  dgpml   ouaga ");

            service.create(request);

            ArgumentCaptor<ProviderVerification> verification = ArgumentCaptor.forClass(ProviderVerification.class);
            verify(verificationRepository).save(verification.capture());
            assertThat(verification.getValue().getLicenceAuthority()).isEqualTo("DGPML OUAGA");
        }

        @Test
        @DisplayName("a HOSPITAL type is refused (400)")
        void hospitalTypeRefused() {
            ProviderCreateRequestDTO request = createRequest(FacilityType.HOSPITAL);
            assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey())
                    .isEqualTo(ProviderOnboardingServiceImpl.MSG_TYPE_INVALID));
            verify(hospitalRepository, never()).save(any());
        }

        @Test
        @DisplayName("an existing code is a 409")
        void duplicateCode() {
            when(hospitalRepository.findByCodeIgnoreCase("PH-OUAGA-1")).thenReturn(Optional.of(new Hospital()));

            ProviderCreateRequestDTO request = createRequest(FacilityType.PHARMACY);

            assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ConflictException.class);
            verify(hospitalRepository, never()).save(any());
        }

        @Test
        @DisplayName("the audit row carries ids, type and status only: no number, no name")
        void auditHasNoBusinessData() {
            service.create(createRequest(FacilityType.PHARMACY));

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(audit.capture());
            AuditEventRequestDTO row = audit.getValue();
            assertThat(row.getEventType()).isEqualTo(AuditEventType.PROVIDER_CREATED);
            assertThat(row.getUserId()).isEqualTo(actorId);
            assertThat(row.getResourceName()).isNull();
            assertThat(row.getEventDescription())
                .doesNotContain("1234", "00012345X", "LIC-77", "ONP-123", "Pharmacie", "Ouédraogo", "Sawadogo", "70 00");
        }

        @Test
        @DisplayName("an unverified super-admin (authority without a live assignment) is refused")
        void unverifiedSuperAdmin() {
            when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);

            ProviderCreateRequestDTO request = createRequest(FacilityType.PHARMACY);

            assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(AccessDeniedException.class);
            verify(hospitalRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("verify (AC-2, AC-3)")
    class Verify {

        @Test
        @DisplayName("both confirmations: VERIFIED, and the facility ACTIVE and active together")
        void verifies() {
            Hospital facility = provider();
            ProviderVerification v = submitted(facility);

            ProviderResponseDTO response = service.verify(facility.getId(), verifyRequest(true, true));

            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
            assertThat(v.isIfuMatchesRccm()).isTrue();
            assertThat(v.isCnssMatchesRccm()).isTrue();
            assertThat(v.getDecidedByUserId()).isEqualTo(actorId);
            assertThat(v.getDecidedAt()).isNotNull();
            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
            assertThat(facility.isActive()).isTrue();
            assertThat(response.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
            verify(lifecycleStatusService).invalidate();
            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PROVIDER_VERIFIED);
        }

        @Test
        @DisplayName("IFU not confirmed: 400 provider.identity.inconsistent, still SUBMITTED, facility untouched")
        void ifuNotConfirmed() {
            assertInconsistent(verifyRequest(false, true));
        }

        @Test
        @DisplayName("CNSS not confirmed: the same refusal")
        void cnssNotConfirmed() {
            assertInconsistent(verifyRequest(true, false));
        }

        @Test
        @DisplayName("confirmations absent: the same refusal")
        void confirmationsAbsent() {
            assertInconsistent(verifyRequest(null, null));
        }

        @Test
        @DisplayName("a licence pair already VERIFIED elsewhere: 409 provider.licence.duplicate")
        void duplicateLicence() {
            Hospital facility = provider();
            ProviderVerification v = submitted(facility);
            when(verificationRepository.existsVerifiedLicenceElsewhere(facility.getId(), "DGPML", "LIC-77"))
                .thenReturn(true);

            UUID id = facility.getId();

            ProviderVerifyRequestDTO request = verifyRequest(true, true);

            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(ConflictException.class);
            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            assertThat(facility.isActive()).isFalse();
        }

        @Test
        @DisplayName("an RCCM or IFU already VERIFIED elsewhere: 409 provider.business.duplicate")
        void duplicateBusiness() {
            Hospital facility = provider();
            submitted(facility);
            when(verificationRepository.existsVerifiedBusinessElsewhere(
                eq(facility.getId()), anyString(), anyString())).thenReturn(true);

            UUID id = facility.getId();

            ProviderVerifyRequestDTO request = verifyRequest(true, true);

            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(ConflictException.class);
            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        }

        @Test
        @DisplayName("a concurrent verify caught by the unique index is a 409 too, not the generic 400")
        void indexRaceIsAConflict() {
            Hospital facility = provider();
            submitted(facility);
            when(verificationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_provider_rccm_verified\""));

            UUID id = facility.getId();

            ProviderVerifyRequestDTO request = verifyRequest(true, true);

            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("corrections replace the captured block before the checks")
        void corrections() {
            Hospital facility = provider();
            ProviderVerification v = submitted(facility);
            ProviderVerifyRequestDTO request = verifyRequest(true, true);
            ProviderBusinessIdentityDTO corrected = business();
            corrected.setRccmNumber("bf-oua-2019-b-9999");
            request.setCorrections(ProviderVerifyRequestDTO.Corrections.builder().business(corrected).build());

            service.verify(facility.getId(), request);

            assertThat(v.getRccmNumber()).isEqualTo("BF-OUA-2019-B-9999");
            verify(verificationRepository).existsVerifiedBusinessElsewhere(facility.getId(), "BF-OUA-2019-B-9999",
                "00012345X");
        }

        @ParameterizedTest(name = "a provider {0} is not activated by VERIFY (409), and stays {0}")
        @EnumSource(value = HospitalLifecycleState.class, names = {"ARCHIVED", "PENDING_PURGE", "PURGED", "ACTIVE"})
        void verifyNeedsAPendingProvider(HospitalLifecycleState state) {
            Hospital facility = provider();
            facility.setLifecycleState(state);
            ProviderVerification v = submitted(facility);

            UUID id = facility.getId();

            ProviderVerifyRequestDTO request = verifyRequest(true, true);

            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(ConflictException.class);
            assertThat(facility.getLifecycleState()).isEqualTo(state);
            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            verify(hospitalRepository, never()).save(any());
        }

        @Test
        @DisplayName("only a SUBMITTED verification can be verified")
        void notSubmitted() {
            Hospital facility = provider();
            submitted(facility).setStatus(ProviderVerificationStatus.REJECTED);

            UUID id = facility.getId();

            ProviderVerifyRequestDTO request = verifyRequest(true, true);

            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a hospital id answers exactly as an unknown id")
        void hospitalIdIsNotAProvider() {
            Hospital hospital = new Hospital();
            hospital.setId(UUID.randomUUID());
            when(hospitalRepository.findById(hospital.getId())).thenReturn(Optional.of(hospital));
            UUID unknown = UUID.randomUUID();

            UUID hospitalId = hospital.getId();
            ProviderVerifyRequestDTO request = verifyRequest(true, true);
            Throwable notProvider = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.verify(hospitalId, request));
            Throwable missing = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.verify(unknown, request));

            assertThat(notProvider).isInstanceOf(ResourceNotFoundException.class);
            assertThat(missing).isInstanceOf(ResourceNotFoundException.class);
            assertThat(notProvider.getMessage()).isEqualTo(missing.getMessage());
        }

        private void assertInconsistent(ProviderVerifyRequestDTO request) {
            Hospital facility = provider();
            ProviderVerification v = submitted(facility);

            UUID id = facility.getId();
            assertThatThrownBy(() -> service.verify(id, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey())
                    .isEqualTo(ProviderOnboardingServiceImpl.MSG_IDENTITY_INCONSISTENT));
            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            assertThat(facility.isActive()).isFalse();
            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
            verify(verificationRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("reject, resubmit, revoke")
    class Decisions {

        @Test
        @DisplayName("reject: REJECTED with the reason, the facility stays SUSPENDED and inactive")
        void reject() {
            Hospital facility = provider();
            ProviderVerification v = submitted(facility);

            service.reject(facility.getId(), new ProviderDecisionRequestDTO("Illegible RCCM extract"));

            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.REJECTED);
            assertThat(v.getDecisionReason()).isEqualTo("Illegible RCCM extract");
            assertThat(facility.isActive()).isFalse();
            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditEventLogService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PROVIDER_REJECTED);
            assertThat(audit.getValue().getEventDescription()).doesNotContain("Illegible");
        }

        @Test
        @DisplayName("resubmit after a rejection: a new SUBMITTED row")
        void resubmit() {
            Hospital facility = provider();
            submitted(facility).setStatus(ProviderVerificationStatus.REJECTED);

            ProviderResponseDTO response = service.resubmit(facility.getId(),
                new ProviderResubmitRequestDTO(business(), professional()));

            assertThat(response.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
            verify(verificationRepository).save(any(ProviderVerification.class));
        }

        @Test
        @DisplayName("resubmit while SUBMITTED is refused")
        void resubmitWhileSubmitted() {
            Hospital facility = provider();
            submitted(facility);

            UUID id = facility.getId();
            ProviderResubmitRequestDTO request = new ProviderResubmitRequestDTO(business(), professional());
            assertThatThrownBy(() -> service.resubmit(id, request))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("revoke: REVOKED, and the facility SUSPENDED and inactive again")
        void revoke() {
            Hospital facility = provider();
            facility.setActive(true);
            facility.setLifecycleState(HospitalLifecycleState.ACTIVE);
            ProviderVerification v = submitted(facility);
            v.setStatus(ProviderVerificationStatus.VERIFIED);

            service.revoke(facility.getId(), new ProviderDecisionRequestDTO("Licence withdrawn"));

            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.REVOKED);
            assertThat(facility.isActive()).isFalse();
            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
            verify(lifecycleStatusService).invalidate();
        }

        @ParameterizedTest(name = "revoke keeps a {0} provider where it is, and inactive")
        @EnumSource(value = HospitalLifecycleState.class, names = {"ARCHIVED", "PENDING_PURGE", "PURGED"})
        void revokeNeverLeavesArchiveOrPurge(HospitalLifecycleState state) {
            Hospital facility = provider();
            facility.setLifecycleState(state);
            Instant purgeAt = Instant.parse("2026-11-08T00:00:00Z");
            facility.setPurgeScheduledFor(purgeAt);
            ProviderVerification v = submitted(facility);
            v.setStatus(ProviderVerificationStatus.VERIFIED);

            service.revoke(facility.getId(), new ProviderDecisionRequestDTO("Licence withdrawn"));

            assertThat(v.getStatus()).isEqualTo(ProviderVerificationStatus.REVOKED);
            assertThat(facility.getLifecycleState()).isEqualTo(state);
            assertThat(facility.getPurgeScheduledFor()).isEqualTo(purgeAt);
            assertThat(facility.getSuspensionReason()).isNull();
            assertThat(facility.isActive()).isFalse();
        }

        @Test
        @DisplayName("revoke of a suspended provider keeps its suspension record")
        void revokeKeepsAnExistingSuspension() {
            Hospital facility = provider();
            facility.setSuspensionReason("Inspection");
            ProviderVerification v = submitted(facility);
            v.setStatus(ProviderVerificationStatus.VERIFIED);

            service.revoke(facility.getId(), new ProviderDecisionRequestDTO("Licence withdrawn"));

            assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
            assertThat(facility.getSuspensionReason()).isEqualTo("Inspection");
        }

        @Test
        @DisplayName("revoke of an unverified provider is refused")
        void revokeUnverified() {
            Hospital facility = provider();
            submitted(facility);

            UUID id = facility.getId();
            ProviderDecisionRequestDTO request = new ProviderDecisionRequestDTO("x");
            assertThatThrownBy(() -> service.revoke(id, request))
                .isInstanceOf(BusinessException.class);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Hospital provider() {
        Hospital facility = Hospital.builder()
            .name("Pharmacie du Marché")
            .code("PH-OUAGA-1")
            .facilityType(FacilityType.PHARMACY)
            .active(false)
            .lifecycleState(HospitalLifecycleState.SUSPENDED)
            .build();
        facility.setId(UUID.randomUUID());
        when(hospitalRepository.findById(facility.getId())).thenReturn(Optional.of(facility));
        return facility;
    }

    private ProviderVerification submitted(Hospital facility) {
        ProviderVerification v = new ProviderVerification();
        v.setId(UUID.randomUUID());
        v.setHospital(facility);
        v.setStatus(ProviderVerificationStatus.SUBMITTED);
        v.setLegalName("Pharmacie du Marché SARL");
        v.setLegalStructure("SARL");
        v.setRccmNumber("BF-OUA-2019-B-1234");
        v.setIfuNumber("00012345X");
        v.setCnssNumber("C-555");
        v.setAddressCity("Ouagadougou");
        v.setAddressRegion("Centre");
        v.setCompanyPhone("+226 70 00 00 00");
        v.setManagerName("Awa Sawadogo");
        v.setManagerTitle("Gérante");
        v.setBusinessStartedOn(LocalDate.of(2019, 3, 1));
        v.setLicenceNumber("LIC-77");
        v.setLicenceAuthority("DGPML");
        v.setResponsibleProfessionalName("Dr Issa Ouédraogo");
        v.setResponsibleProfessionalRegistration("ONP-123");
        when(verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(facility.getId()))
            .thenReturn(Optional.of(v));
        return v;
    }

    private static ProviderVerifyRequestDTO verifyRequest(Boolean ifu, Boolean cnss) {
        return ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(ifu)
            .cnssMatchesRccm(cnss)
            .evidenceNote("Documents checked at the counter")
            .build();
    }

    private static ProviderCreateRequestDTO createRequest(FacilityType type) {
        return ProviderCreateRequestDTO.builder()
            .facilityType(type)
            .code("ph-ouaga-1")
            .business(business())
            .professional(professional())
            .build();
    }

    private static ProviderBusinessIdentityDTO business() {
        return ProviderBusinessIdentityDTO.builder()
            .legalName("Pharmacie du Marché SARL")
            .tradeName("Pharmacie du Marché")
            .legalStructure("SARL")
            .rccmNumber(" bf-oua-2019-b-1234 ")
            .ifuNumber("00012345x")
            .cnssNumber("c-555")
            .address(ProviderAddressDTO.builder()
                .secteur("12").section("BA").lot("3").parcelle("14")
                .city("Ouagadougou").region("Centre").build())
            .companyPhone("+226 70 00 00 00")
            .managerName("Awa Sawadogo")
            .managerTitle("Gérante")
            .startedOn(LocalDate.of(2019, 3, 1))
            .build();
    }

    private static ProviderProfessionalDTO professional() {
        return ProviderProfessionalDTO.builder()
            .licenceNumber("lic-77")
            .licenceAuthority("DGPML")
            .licenceIssuedOn(LocalDate.of(2019, 1, 15))
            .responsibleName("Dr Issa Ouédraogo")
            .responsibleOrdreNumber("onp-123")
            .build();
    }
}
