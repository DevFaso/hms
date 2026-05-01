package com.example.hms.service;

import com.example.hms.enums.DataDomain;
import com.example.hms.enums.ShareScope;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for the new {@code resolveForDomain} entry point that adds scope
 * enforcement and break-the-glass fall-through on top of the existing tier
 * resolution. The plain {@code resolve} contract has its own dedicated test
 * class — these tests focus on the new behaviour only.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentResolutionServiceImpl#resolveForDomain")
class ConsentResolutionDomainTest {

    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientConsentRepository consentRepository;
    @Mock private BreakGlassService breakGlassService;

    private ConsentResolutionServiceImpl service;

    private UUID patientId;
    private UUID requestingHospitalId;
    private UUID sourceHospitalId;
    private UUID orgId;
    private Patient patient;
    private Hospital requestingHospital;
    private Hospital sourceHospital;
    private Organization org;

    @BeforeEach
    void setUp() {
        service = new ConsentResolutionServiceImpl(patientRepository, hospitalRepository, consentRepository);
        ReflectionTestUtils.setField(service, "breakGlassService", breakGlassService);

        patientId = UUID.randomUUID();
        requestingHospitalId = UUID.randomUUID();
        sourceHospitalId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        org = new Organization();
        org.setId(orgId);

        requestingHospital = Hospital.builder().name("City Clinic").build();
        requestingHospital.setId(requestingHospitalId);
        requestingHospital.setOrganization(org);

        sourceHospital = Hospital.builder().name("General Hospital").build();
        sourceHospital.setId(sourceHospitalId);
        sourceHospital.setOrganization(org);

        patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
    }

    @Test
    @DisplayName("SAME_HOSPITAL skips the domain-scope check (data is local)")
    void sameHospitalSkipsScope() {
        patient.getHospitalRegistrations().add(activeReg(requestingHospital));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));

        ConsentResolutionService.ConsentContext ctx =
            service.resolveForDomain(patientId, requestingHospitalId, DataDomain.MENTAL_HEALTH);

        assertThat(ctx.scope()).isEqualTo(ShareScope.SAME_HOSPITAL);
    }

    @Test
    @DisplayName("INTRA_ORG with covering scope returns INTRA_ORG context")
    void intraOrgScopeCovers() {
        patient.getHospitalRegistrations().add(activeReg(sourceHospital));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
            .thenReturn(java.util.List.of(sourceHospital, requestingHospital));

        PatientConsent consent = consentWithScope(sourceHospital, requestingHospital, "PRESCRIPTIONS,LAB_RESULTS");
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, sourceHospitalId, requestingHospitalId))
            .thenReturn(Optional.of(consent));

        ConsentResolutionService.ConsentContext ctx =
            service.resolveForDomain(patientId, requestingHospitalId, DataDomain.PRESCRIPTIONS);

        assertThat(ctx.scope()).isEqualTo(ShareScope.INTRA_ORG);
        assertThat(ctx.consent()).isSameAs(consent);
    }

    @Test
    @DisplayName("INTRA_ORG match whose scope omits the requested domain falls through to break-glass")
    void scopeMissFallsThroughToBreakGlass() {
        patient.getHospitalRegistrations().add(activeReg(sourceHospital));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
            .thenReturn(java.util.List.of(sourceHospital, requestingHospital));

        PatientConsent consent = consentWithScope(sourceHospital, requestingHospital, "PRESCRIPTIONS"); // no IMAGING
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, sourceHospitalId, requestingHospitalId))
            .thenReturn(Optional.of(consent));

        when(breakGlassService.consumeIfLive(eq(patientId), any(), any()))
            .thenReturn(Optional.of(BreakGlassSessionResponseDTO.builder().id(UUID.randomUUID()).live(true).build()));

        ConsentResolutionService.ConsentContext ctx =
            service.resolveForDomain(patientId, requestingHospitalId, DataDomain.IMAGING);

        assertThat(ctx.scope()).isEqualTo(ShareScope.BREAK_GLASS);
        assertThat(ctx.consent()).isNull();
    }

    @Test
    @DisplayName("Throws when no consent and no break-glass session covers the domain")
    void noConsentNoBreakGlassThrows() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
        when(breakGlassService.consumeIfLive(eq(patientId), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveForDomain(patientId, requestingHospitalId, DataDomain.HIV_STATUS))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("HIV_STATUS");
    }

    @Test
    @DisplayName("Sensitive domain requires explicit listing — generic 'all-domains' scope is not enough")
    void sensitiveDomainRequiresExplicitScope() {
        patient.getHospitalRegistrations().add(activeReg(sourceHospital));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(requestingHospitalId)).thenReturn(Optional.of(requestingHospital));
        when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId))
            .thenReturn(java.util.List.of(sourceHospital, requestingHospital));

        // Null scope = "all non-sensitive" — must NOT cover MENTAL_HEALTH.
        PatientConsent consent = consentWithScope(sourceHospital, requestingHospital, null);
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(
                patientId, sourceHospitalId, requestingHospitalId))
            .thenReturn(Optional.of(consent));
        when(breakGlassService.consumeIfLive(eq(patientId), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveForDomain(patientId, requestingHospitalId, DataDomain.MENTAL_HEALTH))
            .isInstanceOf(BusinessException.class);
    }

    // -- helpers ---------------------------------------------------------

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

    private PatientConsent consentWithScope(Hospital from, Hospital to, String scope) {
        PatientConsent c = PatientConsent.builder()
            .patient(patient)
            .fromHospital(from)
            .toHospital(to)
            .consentGiven(true)
            .consentExpiration(null)
            .purpose("Test")
            .scope(scope)
            .build();
        c.setId(UUID.randomUUID());
        return c;
    }
}
