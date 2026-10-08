package com.example.hms.service.provider;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.provider.ProviderAddressDTO;
import com.example.hms.payload.dto.provider.ProviderBusinessIdentityDTO;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderProfessionalDTO;
import com.example.hms.payload.dto.provider.ProviderResponseDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.HospitalLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider plan AC-1, AC-4, AC-16 and T22 on the real services and the real
 * (unchanged) {@link TenantLifecycleGate}:
 *
 * <ul>
 *   <li>a new provider is SUSPENDED, so a user assigned there is answered 423
 *       by the gate (both auth paths call the same gate);</li>
 *   <li>the generic hospital-lifecycle restore refuses an unverified provider
 *       with 409; VERIFY is what makes it ACTIVE;</li>
 *   <li>a verified provider suspended later is blocked again, and its restore
 *       is allowed; a revoked one is refused again;</li>
 *   <li>the boot jobs' finder never returns a provider.</li>
 * </ul>
 */
@Transactional
class ProviderLifecycleIT extends BaseIT {

    @Autowired private ProviderOnboardingService onboardingService;
    @Autowired private HospitalLifecycleService lifecycleService;
    @Autowired private TenantLifecycleGate lifecycleGate;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private com.example.hms.service.HospitalService hospitalService;
    @Autowired private com.example.hms.repository.provider.ProviderVerificationRepository verificationRepository;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void signInAsVerifiedSuperAdmin() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .superAdmin(true)
            .build());
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("created SUSPENDED and inactive: a user assigned there is blocked (423)")
    void newProviderBlocksItsUsers() {
        ProviderResponseDTO created = onboardingService.create(request(FacilityType.PHARMACY));

        assertThat(created.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(created.isActive()).isFalse();
        assertThat(lifecycleGate.isBlocked(userAt(created.getId()))).isTrue();
    }

    @Test
    @DisplayName("the generic restore of an unverified provider answers 409; VERIFY makes it ACTIVE")
    void restoreNeedsVerification() {
        UUID id = onboardingService.create(request(FacilityType.LABORATORY)).getId();

        assertThatThrownBy(() -> lifecycleService.restore(id, null)).isInstanceOf(ConflictException.class);
        assertThat(hospitalRepository.findById(id).orElseThrow().getLifecycleState())
            .isEqualTo(HospitalLifecycleState.SUSPENDED);

        ProviderResponseDTO verified = onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());

        assertThat(verified.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
        Hospital facility = hospitalRepository.findById(id).orElseThrow();
        assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        assertThat(facility.isActive()).isTrue();
        assertThat(lifecycleGate.isBlocked(userAt(id))).isFalse();
    }

    @Test
    @DisplayName("suspend a verified provider: blocked again; its restore is allowed (AC-16)")
    void suspendAndRestoreAVerifiedProvider() {
        UUID id = verifiedProvider();

        lifecycleService.suspend(id, TenantLifecycleActionRequestDTO.builder().reason("Inspection").build(), null);
        assertThat(lifecycleGate.isBlocked(userAt(id))).isTrue();

        lifecycleService.restore(id, null);
        assertThat(lifecycleGate.isBlocked(userAt(id))).isFalse();
    }

    @Test
    @DisplayName("a revoked provider is suspended, and the generic restore refuses it again")
    void revokedProviderStaysOut() {
        UUID id = verifiedProvider();

        onboardingService.revoke(id, new ProviderDecisionRequestDTO("Licence withdrawn"));

        assertThat(lifecycleGate.isBlocked(userAt(id))).isTrue();
        assertThatThrownBy(() -> lifecycleService.restore(id, null)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the boot jobs never see a provider without an organisation")
    void bootFinderSkipsProviders() {
        UUID id = onboardingService.create(request(FacilityType.PHARMACY)).getId();

        assertThat(hospitalRepository.findByOrganizationIsNull()).extracting(Hospital::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("one licence is one facility, whatever the authority's case and spacing (AC-3)")
    void licenceAuthorityIsCaseInsensitive() {
        ProviderCreateRequestDTO first = request(FacilityType.PHARMACY);
        first.getProfessional().setLicenceAuthority("DGPML");
        ProviderCreateRequestDTO second = request(FacilityType.PHARMACY);
        second.getProfessional().setLicenceAuthority("  dgpml ");
        second.getProfessional().setLicenceNumber(first.getProfessional().getLicenceNumber());
        UUID one = onboardingService.create(first).getId();
        UUID two = onboardingService.create(second).getId();
        onboardingService.verify(one, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());

        ProviderVerifyRequestDTO confirmed = ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build();
        assertThatThrownBy(() -> onboardingService.verify(two, confirmed))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("archived before verification: restore brings it back SUSPENDED, then VERIFY makes it ACTIVE")
    void archivedUnverifiedProviderCanStillBeVerified() {
        UUID id = onboardingService.create(request(FacilityType.PHARMACY)).getId();
        lifecycleService.archive(id, TenantLifecycleActionRequestDTO.builder().reason("Paused").build(), null);

        lifecycleService.restore(id, null);

        Hospital restored = hospitalRepository.findById(id).orElseThrow();
        assertThat(restored.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(restored.isActive()).isFalse();
        assertThat(lifecycleGate.isBlocked(userAt(id))).isTrue();

        onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());
        assertThat(hospitalRepository.findById(id).orElseThrow().getLifecycleState())
            .isEqualTo(HospitalLifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("archived and revoked: restore brings it back SUSPENDED; resubmit and VERIFY reopen it")
    void archivedRevokedProviderComesBackPending() {
        UUID id = verifiedProvider();
        lifecycleService.archive(id, TenantLifecycleActionRequestDTO.builder().reason("Closed").build(), null);
        onboardingService.revoke(id, new ProviderDecisionRequestDTO("Licence withdrawn"));

        lifecycleService.restore(id, null);

        assertThat(hospitalRepository.findById(id).orElseThrow().getLifecycleState())
            .isEqualTo(HospitalLifecycleState.SUSPENDED);
        ProviderCreateRequestDTO evidence = request(FacilityType.PHARMACY);
        onboardingService.resubmit(id, com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO.builder()
            .business(evidence.getBusiness()).professional(evidence.getProfessional()).build());
        onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());
        assertThat(hospitalRepository.findById(id).orElseThrow().getLifecycleState())
            .isEqualTo(HospitalLifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("rejected, resubmitted with a new trade name, verified: the facility goes live under the new name")
    void resubmittedIdentityIsTheOneThatGoesLive() {
        ProviderCreateRequestDTO first = request(FacilityType.PHARMACY);
        first.getBusiness().setTradeName("Old Name");
        UUID id = onboardingService.create(first).getId();
        onboardingService.reject(id, new ProviderDecisionRequestDTO("Wrong trade name"));
        ProviderCreateRequestDTO evidence = request(FacilityType.PHARMACY);
        evidence.getBusiness().setTradeName("Corrected Name");
        evidence.getBusiness().setCompanyPhone("+22671111111");
        onboardingService.resubmit(id, com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO.builder()
            .business(evidence.getBusiness()).professional(evidence.getProfessional()).build());

        onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());

        Hospital facility = hospitalRepository.findById(id).orElseThrow();
        assertThat(facility.getName()).isEqualTo("Corrected Name");
        assertThat(facility.getPhoneNumber()).isEqualTo("+22671111111");
        assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("an operator suspension survives revoke, resubmit and verify; only the lifecycle restore lifts it")
    void operatorSuspensionSurvivesReVerification() {
        UUID id = verifiedProvider();
        lifecycleService.suspend(id, TenantLifecycleActionRequestDTO.builder().reason("Fraud").build(), null);
        onboardingService.revoke(id, new ProviderDecisionRequestDTO("Licence withdrawn"));
        ProviderCreateRequestDTO evidence = request(FacilityType.PHARMACY);
        onboardingService.resubmit(id, com.example.hms.payload.dto.provider.ProviderResubmitRequestDTO.builder()
            .business(evidence.getBusiness()).professional(evidence.getProfessional()).build());

        ProviderResponseDTO verified = onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());

        assertThat(verified.getVerificationStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
        assertThat(verified.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(lifecycleGate.isBlocked(userAt(id))).isTrue();
        assertThat(lifecycleService.getLifecycle(id).isCanRestore()).isTrue();

        lifecycleService.restore(id, null);
        assertThat(lifecycleGate.isBlocked(userAt(id))).isFalse();
    }

    @Test
    @DisplayName("DELETE of a provider onboarded by mistake removes it and its verification history")
    void deleteUnverifiedProvider() {
        UUID id = onboardingService.create(request(FacilityType.LABORATORY)).getId();
        onboardingService.reject(id, new ProviderDecisionRequestDTO("Wrong business"));
        entityManager.flush();
        entityManager.clear();

        hospitalService.deleteHospital(id, java.util.Locale.ENGLISH);
        entityManager.flush();
        entityManager.clear();

        assertThat(hospitalRepository.findById(id)).isEmpty();
        assertThat(verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(id)).isEmpty();
    }

    @Test
    @DisplayName("DELETE of a provider that has been verified is refused (409); it stays")
    void deleteVerifiedProviderIsRefused() {
        UUID id = verifiedProvider();

        assertThatThrownBy(() -> hospitalService.deleteHospital(id, java.util.Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);
        assertThat(hospitalRepository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("the provider list is newest first and stable across pages: no row repeats, none is skipped")
    void providerListIsStable() throws InterruptedException {
        java.util.List<UUID> created = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(onboardingService.create(request(FacilityType.LABORATORY)).getId());
            Thread.sleep(5);
        }
        java.util.Collections.reverse(created);

        java.util.List<UUID> listed = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            onboardingService.list(FacilityType.LABORATORY, ProviderVerificationStatus.SUBMITTED,
                    org.springframework.data.domain.PageRequest.of(page, 2,
                        org.springframework.data.domain.Sort.by("legalName")))
                .forEach(dto -> listed.add(dto.getId()));
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(created::contains).toList()).containsExactlyElementsOf(created);
    }

    private UUID verifiedProvider() {
        UUID id = onboardingService.create(request(FacilityType.PHARMACY)).getId();
        onboardingService.verify(id, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());
        return id;
    }

    private static HospitalContext userAt(UUID facilityId) {
        return HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .permittedHospitalIds(Set.of(facilityId))
            .build();
    }

    /** Unique numbers per call: the H2 database is shared by every test in the context. */
    private static ProviderCreateRequestDTO request(FacilityType type) {
        String n = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return ProviderCreateRequestDTO.builder()
            .facilityType(type)
            .code("PRV-" + n)
            .business(ProviderBusinessIdentityDTO.builder()
                .legalName("Business " + n + " SARL")
                .legalStructure("SARL")
                .rccmNumber("RCCM-" + n)
                .ifuNumber("IFU-" + n)
                .cnssNumber("CNSS-" + n)
                .address(ProviderAddressDTO.builder().city("Ouagadougou").region("Centre").build())
                .companyPhone("+22670000000")
                .managerName("Manager " + n)
                .managerTitle("Gérant")
                .startedOn(LocalDate.of(2020, 1, 1))
                .build())
            .professional(ProviderProfessionalDTO.builder()
                .licenceNumber("LIC-" + n)
                .licenceAuthority("Authority")
                .responsibleName("Responsible " + n)
                .responsibleOrdreNumber("ORD-" + n)
                .build())
            .build();
    }
}
