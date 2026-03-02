package com.example.hms.service;

import com.example.hms.enums.ShareScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashSet;
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
@DisplayName("ConsentResolutionServiceImpl")
class ConsentResolutionServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientConsentRepository consentRepository;

    @InjectMocks private ConsentResolutionServiceImpl service;

    // ── Shared fixtures ────────────────────────────────────────────────────

    private UUID patientId;
    private UUID requestingHospitalId;
    private UUID siblingHospitalId;
    private UUID crossOrgHospitalId;
    private UUID orgId;
    private UUID otherOrgId;

    private Organization org;
    private Organization otherOrg;
    private Hospital requestingHospital;
    private Hospital siblingHospital;
    private Hospital crossOrgHospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        patientId           = UUID.randomUUID();
        requestingHospitalId = UUID.randomUUID();
        siblingHospitalId   = UUID.randomUUID();
        crossOrgHospitalId  = UUID.randomUUID();
        orgId               = UUID.randomUUID();
        otherOrgId          = UUID.randomUUID();

        org = new Organization();
        org.setId(orgId);
        org.setName("HealthGroup West");

        otherOrg = new Organization();
        otherOrg.setId(otherOrgId);
        otherOrg.setName("ExternalNet");

        requestingHospital = Hospital.builder().name("City Clinic").build();
        requestingHospital.setId(requestingHospitalId);
        requestingHospital.setOrganization(org);

        siblingHospital = Hospital.builder().name("General Hospital").build();
        siblingHospital.setId(siblingHospitalId);
        siblingHospital.setOrganization(org);

        crossOrgHospital = Hospital.builder().name("Cross Hospital").build();
        crossOrgHospital.setId(crossOrgHospitalId);
        crossOrgHospital.setOrganization(otherOrg);

        patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
    }

    // ── Helper builders ────────────────────────────────────────────────────

    private PatientHospitalRegistration activeReg(Hospital hospital) {
        PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
            .mrn("MRN-" + hospital.getId())
            .patient(patient)
            .hospital(hospital)
            .active(true)
            .registrationDate(java.time.LocalDate.now())
            .build();
        reg.setId(UUID.randomUUID());
        return reg;
    }

    private PatientHospitalRegistration inactiveReg(Hospital hospital) {
        PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
            .mrn("MRN-" + hospital.getId())
            .patient(patient)
            .hospital(hospital)
            .active(false)
            .registrationDate(java.time.LocalDate.now())
            .build();
        reg.setId(UUID.randomUUID());
        return reg;
    }

    private PatientConsent activeConsent(Hospital from, Hospital to) {
        PatientConsent c = PatientConsent.builder()
            .patient(patient)
            .fromHospital(from)
            .toHospital(to)
            .consentGiven(true)
            .consentExpiration(null) // never expires
            .purpose("Routine care")
            .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private PatientConsent expiredConsent(Hospital from, Hospital to) {
        PatientConsent c = PatientConsent.builder()
            .patient(patient)
            .fromHospital(from)
            .toHospital(to)
            .consentGiven(true)
            .consentExpiration(LocalDateTime.now().minusDays(1))
            .purpose("Expired")
            .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    // ──────────────────────────────────────────────────────────────────────
    // resolve() — error paths
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Precondition failures")
    class PreconditionTests {

        @Test
        @DisplayName("throws ResourceNotFoundException when patient not found")
        void patientNotFound() {
            when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(patientId, requestingHospitalId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(patientId.toString());

            verify(hospitalRepository, never()).findById(any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when requesting hospital not found")
        void hospitalNotFound() {
            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(patientId, requestingHospitalId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(requestingHospitalId.toString());
        }

        @Test
        @DisplayName("throws BusinessException when no consent exists at any tier")
        void noConsentAtAnyTier() {
            // Patient is registered at cross-org hospital only — no active consent to requestingHospital
            patient.getHospitalRegistrations().add(activeReg(crossOrgHospital));

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(patientId, requestingHospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active consent");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 1 — SAME_HOSPITAL
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 1 — SAME_HOSPITAL")
    class Tier1Tests {

        @Test
        @DisplayName("resolves SAME_HOSPITAL when patient is actively registered at the requesting hospital")
        void sameHospital_activeRegistration() {
            patient.getHospitalRegistrations().add(activeReg(requestingHospital));

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.SAME_HOSPITAL);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(requestingHospitalId);
            assertThat(ctx.requestingHospital().getId()).isEqualTo(requestingHospitalId);
            assertThat(ctx.consent()).isNull();
            assertThat(ctx.patient().getId()).isEqualTo(patientId);
            assertThat(ctx.isSelfServe()).isTrue();

            // Consent table must never be queried for Tier 1
            verify(consentRepository, never())
                .findByPatientIdAndFromHospitalIdAndToHospitalId(any(), any(), any());
        }

        @Test
        @DisplayName("does NOT resolve SAME_HOSPITAL when registration is inactive")
        void sameHospital_inactiveRegistration_escalates() {
            patient.getHospitalRegistrations().add(inactiveReg(requestingHospital));
            // Also register at sibling so Tier 2 can resolve it
            patient.getHospitalRegistrations().add(activeReg(siblingHospital));

            PatientConsent consent = activeConsent(siblingHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, siblingHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(consent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            // Must escalate to Tier 2
            assertThat(ctx.scope()).isEqualTo(ShareScope.INTRA_ORG);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 2 — INTRA_ORG
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 2 — INTRA_ORG")
    class Tier2Tests {

        @BeforeEach
        void registerAtSibling() {
            // Patient is NOT at requestingHospital, but IS at the sibling
            patient.getHospitalRegistrations().add(activeReg(siblingHospital));
        }

        @Test
        @DisplayName("resolves INTRA_ORG when sibling hospital has active consent")
        void intraOrg_activeConsent() {
            PatientConsent consent = activeConsent(siblingHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, siblingHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(consent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.INTRA_ORG);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(siblingHospitalId);
            assertThat(ctx.requestingHospital().getId()).isEqualTo(requestingHospitalId);
            assertThat(ctx.consent()).isNotNull();
            assertThat(ctx.consent().getId()).isEqualTo(consent.getId());
            assertThat(ctx.isSelfServe()).isFalse();
        }

        @Test
        @DisplayName("skips requesting hospital when walking sibling list")
        void intraOrg_skipsRequestingHospitalItself() {
            PatientConsent consent = activeConsent(siblingHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            // Org returns both requesting hospital AND the sibling
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(requestingHospital, siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, siblingHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(consent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.INTRA_ORG);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(siblingHospitalId);
        }

        @Test
        @DisplayName("escalates to Tier 3 when sibling consent is expired")
        void intraOrg_expiredConsent_escalatesToTier3() {
            PatientConsent expired = expiredConsent(siblingHospital, requestingHospital);
            // Also register at crossOrgHospital so Tier 3 can resolve
            patient.getHospitalRegistrations().add(activeReg(crossOrgHospital));
            PatientConsent crossConsent = activeConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, siblingHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(expired));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(crossConsent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
        }

        @Test
        @DisplayName("returns INTRA_ORG when requesting hospital has no organization (skips Tier 2 entirely)")
        void intraOrg_requestingHospitalHasNoOrg_skipsTier2() {
            requestingHospital.setOrganization(null);

            // Register at crossOrgHospital so Tier 3 can succeed
            patient.getHospitalRegistrations().clear();
            patient.getHospitalRegistrations().add(activeReg(crossOrgHospital));
            PatientConsent crossConsent = activeConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(crossConsent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            // Tier 2 was skipped; should jump to Tier 3
            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
            verify(hospitalRepository, never()).findByOrganizationIdOrderByNameAsc(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tier 3 — CROSS_ORG
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 3 — CROSS_ORG")
    class Tier3Tests {

        @BeforeEach
        void registerAtCrossOrgOnly() {
            patient.getHospitalRegistrations().add(activeReg(crossOrgHospital));
        }

        @Test
        @DisplayName("resolves CROSS_ORG when an explicit cross-org consent exists")
        void crossOrg_activeConsent() {
            PatientConsent consent = activeConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital)); // sibling has no patient reg, so Tier 2 yields nothing
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(consent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(crossOrgHospitalId);
            assertThat(ctx.requestingHospital().getId()).isEqualTo(requestingHospitalId);
            assertThat(ctx.consent()).isNotNull();
            assertThat(ctx.isSelfServe()).isFalse();
        }

        @Test
        @DisplayName("skips intra-org registrations when walking Tier 3")
        void crossOrg_skipsIntraOrgHospitals() {
            // Add both sibling (intra-org) and crossOrg registrations
            patient.getHospitalRegistrations().add(activeReg(siblingHospital));
            PatientConsent crossConsent = activeConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            // Tier 2: sibling org consent is absent
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, siblingHospitalId, requestingHospitalId))
                .thenReturn(Optional.empty());
            // Tier 3: crossOrg consent is present
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(crossConsent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(crossOrgHospitalId);
        }

        @Test
        @DisplayName("skips registration where hospital has no organization (null org treated as different org)")
        void crossOrg_hospitalWithNullOrg_treatedAsCrossOrg() {
            // A hospital with null org — sameOrg() returns false, so it's treated as cross-org
            Hospital noOrgHospital = Hospital.builder().name("No-Org Hospital").build();
            noOrgHospital.setId(UUID.randomUUID());
            noOrgHospital.setOrganization(null);  // <-- triggers sameOrg null branch

            patient.getHospitalRegistrations().clear();
            patient.getHospitalRegistrations().add(activeReg(noOrgHospital));
            PatientConsent consent = activeConsent(noOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of());
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, noOrgHospital.getId(), requestingHospitalId))
                .thenReturn(Optional.of(consent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
            assertThat(ctx.sourceHospital().getId()).isEqualTo(noOrgHospital.getId());
        }

        @Test
        @DisplayName("skips the requesting hospital itself in Tier 3 stream (inactive reg at requesting hospital)")
        void crossOrg_skipsRequestingHospitalOwnRegistration() {
            // Inactive reg at requesting hospital — Tier 1 won't fire.
            // Active reg at crossOrgHospital — Tier 3 should skip the requesting hospital
            // entry in the stream (filter: h != requestingHospital) and use crossOrg.
            patient.getHospitalRegistrations().add(inactiveReg(requestingHospital));
            PatientConsent crossConsent = activeConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of());
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(crossConsent));

            ConsentResolutionService.ConsentContext ctx = service.resolve(patientId, requestingHospitalId);

            assertThat(ctx.scope()).isEqualTo(ShareScope.CROSS_ORG);
        }

        @Test
        @DisplayName("throws BusinessException when cross-org consent is expired")
        void crossOrg_expiredConsent_throws() {
            PatientConsent expired = expiredConsent(crossOrgHospital, requestingHospital);

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.resolve(patientId, requestingHospitalId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active consent");
        }

        @Test
        @DisplayName("throws BusinessException when cross-org consent is revoked (consentGiven=false)")
        void crossOrg_revokedConsent_throws() {
            PatientConsent revoked = PatientConsent.builder()
                .patient(patient)
                .fromHospital(crossOrgHospital)
                .toHospital(requestingHospital)
                .consentGiven(false)
                .consentExpiration(null)
                .build();
            revoked.setId(UUID.randomUUID());

            when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
            when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
                .thenReturn(List.of(siblingHospital));
            when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                    patientId, crossOrgHospitalId, requestingHospitalId))
                .thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.resolve(patientId, requestingHospitalId))
                .isInstanceOf(BusinessException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ConsentContext record behaviour
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConsentContext record")
    class ConsentContextTests {

        @Test
        @DisplayName("isSelfServe() returns true only for SAME_HOSPITAL")
        void isSelfServe_onlyTrueForSameHospital() {
            ConsentResolutionService.ConsentContext same =
                new ConsentResolutionService.ConsentContext(
                    ShareScope.SAME_HOSPITAL, requestingHospital, requestingHospital, null, patient);
            ConsentResolutionService.ConsentContext intra =
                new ConsentResolutionService.ConsentContext(
                    ShareScope.INTRA_ORG, siblingHospital, requestingHospital, activeConsent(siblingHospital, requestingHospital), patient);
            ConsentResolutionService.ConsentContext cross =
                new ConsentResolutionService.ConsentContext(
                    ShareScope.CROSS_ORG, crossOrgHospital, requestingHospital, activeConsent(crossOrgHospital, requestingHospital), patient);

            assertThat(same.isSelfServe()).isTrue();
            assertThat(intra.isSelfServe()).isFalse();
            assertThat(cross.isSelfServe()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ShareScope enum
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ShareScope enum labels")
    class ShareScopeTests {

        @Test
        @DisplayName("each scope has the correct human-readable label")
        void labels() {
            assertThat(ShareScope.SAME_HOSPITAL.getLabel()).isEqualTo("Same-hospital access");
            assertThat(ShareScope.INTRA_ORG.getLabel()).isEqualTo("Intra-organisation share");
            assertThat(ShareScope.CROSS_ORG.getLabel()).isEqualTo("Cross-organisation share");
        }
    }
}
