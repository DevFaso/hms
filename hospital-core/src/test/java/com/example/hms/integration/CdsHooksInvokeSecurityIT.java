package com.example.hms.integration;

import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.BaseIT;
import com.example.hms.cdshooks.service.CdsHookRegistry;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientProblem;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /cds-services/{id}} through the REAL security filter chain —
 * a signed Keycloak-style bearer token, the resource server, the Keycloak
 * hospital-context filter, the URL matchers and method security — for every
 * registered CDS service.
 *
 * <p>Each service reads the chart of the patient {@code context.patientId}
 * names, so each must be refused to a non-clinician, and must answer a
 * patient the caller cannot read at their hospital exactly as it answers one
 * that does not exist. The chart rows are mocked: what is under test is who
 * gets past the controller, and whether the chart is ever read for them.
 */
@AutoConfigureMockMvc
@Import(CdsHooksInvokeSecurityIT.SignedKeycloakTokens.class)
class CdsHooksInvokeSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();

    /** Every registered service, with the hook it advertises. */
    private static final Map<String, String> SERVICES = Map.of(
        "hms-patient-view", "patient-view",
        "hms-bpa-protocols", "patient-view",
        "hms-order-select-rules", "order-select",
        "hms-medication-prescribe-rules", "medication-prescribe",
        "hms-order-sign-rules", "order-sign",
        "hms-medication-allergy-check", "order-sign");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CdsHookRegistry registry;

    @MockitoBean private PatientRepository patientRepository;
    @MockitoBean private PatientHospitalRegistrationRepository registrationRepository;
    @MockitoBean private RecordAccessPolicy recordAccessPolicy;
    @MockitoBean private PatientAllergyRepository allergyRepository;
    @MockitoBean private PatientProblemRepository problemRepository;
    @Autowired private com.example.hms.repository.HospitalRepository hospitalRepository;
    @Autowired private com.example.hms.repository.UserRepository userRepository;
    @Autowired private com.example.hms.repository.RoleRepository roleRepository;
    @Autowired private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private com.example.hms.repository.AuditEventLogRepository auditEventLogRepository;
    /** Real local accounts: the Keycloak path places a caller by the appUserId account's live assignments. */
    private com.example.hms.security.tenant.LinkedTestAccounts accounts;
    @Autowired private com.example.hms.security.IdleSessionTracker idleSessionTracker;

    private UUID hospitalA;
    private UUID hospitalB;
    /** Registered at hospital A only. */
    private final UUID patientAtA = UUID.randomUUID();
    private final UUID unknownPatient = UUID.randomUUID();

    @BeforeEach
    void seedTheChart() {
        accounts = new com.example.hms.security.tenant.LinkedTestAccounts(
            hospitalRepository, userRepository, roleRepository, assignmentRepository, auditEventLogRepository);
        hospitalA = accounts.hospital("CDS A").getId();
        hospitalB = accounts.hospital("CDS B").getId();
        when(patientRepository.findByIdUnscoped(patientAtA)).thenReturn(Optional.of(patient(patientAtA, false)));
        when(patientRepository.findByIdUnscoped(unknownPatient)).thenReturn(Optional.empty());
        when(registrationRepository.existsByPatientIdAndHospitalId(patientAtA, hospitalA)).thenReturn(true);
        when(recordAccessPolicy.decide(any(), any(), any())).thenAnswer(inv -> RecordAccessDecision.refused(
            inv.getArgument(1), inv.getArgument(2), inv.getArgument(0),
            RecordAccessDenialReason.NO_TREATMENT_RELATIONSHIP, null));

        PatientAllergy allergy = PatientAllergy.builder()
            .allergenDisplay("Penicillin")
            .severity(AllergySeverity.SEVERE)
            .build();
        PatientProblem problem = PatientProblem.builder()
            .problemDisplay("Sickle cell disease")
            .build();
        when(allergyRepository.findByPatient_Id(patientAtA)).thenReturn(List.of(allergy));
        when(problemRepository.findByPatient_Id(patientAtA)).thenReturn(List.of(problem));
    }

    @org.junit.jupiter.api.AfterEach
    void removeTheAccounts() {
        accounts.cleanUp();
    }

    private Patient patient(UUID id, boolean restricted) {
        Patient p = new Patient();
        p.setId(id);
        p.setHospitalId(hospitalA);
        p.setChartRestricted(restricted);
        return p;
    }

    private MvcResult invoke(String token, String serviceId, UUID patientId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hook", SERVICES.get(serviceId));
        body.put("hookInstance", UUID.randomUUID().toString());
        body.put("context", Map.of(
            "patientId", "Patient/" + patientId,
            // A penicillin draft: the order services would test it against the
            // chart's allergies and prescriptions if they were let through.
            "draftOrders", Map.of("entry", List.of(Map.of("resource", Map.of(
                "resourceType", "MedicationRequest",
                "id", "draft-1",
                "medicationCodeableConcept", Map.of("text", "Penicillin V 500 mg")))))));
        return mockMvc.perform(post("/cds-services/{id}", serviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    }

    private static String shape(MvcResult result) throws Exception {
        return result.getResponse().getStatus() + "|" + result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("the services exercised here are every registered service, with the hook each advertises")
    void coversEveryRegisteredService() {
        Map<String, String> registered = new LinkedHashMap<>();
        registry.descriptors().forEach(d -> registered.put(d.id(), d.hook()));
        assertThat(registered).containsExactlyInAnyOrderEntriesOf(SERVICES);
    }

    @Test
    @DisplayName("a doctor at the patient's hospital gets the allergy and problem-list cards")
    void clinicianAtThePatientsHospitalGetsTheCards() throws Exception {
        String doctor = linkedToken("doctor-a", hospitalA, "DOCTOR");

        MvcResult summary = invoke(doctor, "hms-patient-view", patientAtA);
        assertThat(summary.getResponse().getStatus()).isEqualTo(200);
        assertThat(summary.getResponse().getContentAsString())
            .contains("Penicillin")
            .contains("Sickle cell disease");

        MvcResult allergyCheck = invoke(doctor, "hms-medication-allergy-check", patientAtA);
        assertThat(allergyCheck.getResponse().getStatus()).isEqualTo(200);
        assertThat(allergyCheck.getResponse().getContentAsString()).contains("Allergy alert");
    }

    @Test
    @DisplayName("a patient token is refused every CDS service, and no chart is read")
    void patientTokenIsRefused() throws Exception {
        String patient = linkedToken("patient001", hospitalA, "PATIENT");
        for (String serviceId : SERVICES.keySet()) {
            assertThat(invoke(patient, serviceId, patientAtA).getResponse().getStatus())
                .as(serviceId).isEqualTo(403);
        }
        verify(allergyRepository, never()).findByPatient_Id(any());
        verify(problemRepository, never()).findByPatient_Id(any());
        verify(patientRepository, never()).findByIdUnscoped(any());
    }

    @Test
    @DisplayName("a receptionist is refused too — the cards are clinical")
    void receptionistIsRefused() throws Exception {
        String receptionist = linkedToken("desk-a", hospitalA, "RECEPTIONIST");
        for (String serviceId : SERVICES.keySet()) {
            assertThat(invoke(receptionist, serviceId, patientAtA).getResponse().getStatus())
                .as(serviceId).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a doctor at another hospital gets the same answer for A's patient as for an unknown one, from every service")
    void foreignPatientAnswersAsUnknown() throws Exception {
        String doctorB = linkedToken("doctor-b", hospitalB, "DOCTOR");
        for (String serviceId : SERVICES.keySet()) {
            MvcResult foreign = invoke(doctorB, serviceId, patientAtA);
            MvcResult unknown = invoke(doctorB, serviceId, unknownPatient);
            assertThat(shape(foreign)).as(serviceId)
                .isEqualTo(shape(unknown))
                .isEqualTo("200|{\"cards\":[]}");
        }
        // The gate stops before any service touches the chart.
        verify(allergyRepository, never()).findByPatient_Id(any());
        verify(problemRepository, never()).findByPatient_Id(any());
        verify(recordAccessPolicy, times(SERVICES.size())).decide(any(), eq(patientAtA), eq(hospitalB));
    }

    @Test
    @DisplayName("a restricted chart at the caller's own hospital answers as unknown, not with a 403 that confirms it")
    void restrictedChartAnswersAsUnknown() throws Exception {
        when(patientRepository.findByIdUnscoped(patientAtA)).thenReturn(Optional.of(patient(patientAtA, true)));
        when(recordAccessPolicy.decide(any(), eq(patientAtA), eq(hospitalA))).thenAnswer(inv -> RecordAccessDecision.refused(
            patientAtA, hospitalA, inv.getArgument(0), RecordAccessDenialReason.CHART_RESTRICTED, null));
        String doctor = linkedToken("doctor-a", hospitalA, "DOCTOR");

        MvcResult restricted = invoke(doctor, "hms-patient-view", patientAtA);
        MvcResult unknown = invoke(doctor, "hms-patient-view", unknownPatient);
        assertThat(shape(restricted)).isEqualTo(shape(unknown)).isEqualTo("200|{\"cards\":[]}");
        verify(allergyRepository, never()).findByPatient_Id(any());
    }

    @Test
    @DisplayName("discovery stays public and carries no patient data")
    void discoveryStaysPublic() throws Exception {
        MvcResult discovery = mockMvc.perform(get("/cds-services")).andReturn();
        assertThat(discovery.getResponse().getStatus()).isEqualTo(200);
        assertThat(discovery.getResponse().getContentAsString())
            .contains("hms-patient-view")
            .doesNotContain("Penicillin");
    }

    // ── token minting (as PatientSubjectReadSecurityIT) ────────────────────

    /**
     * A token for a real local account holding {@code roles} at {@code hospitalId}: the
     * appUserId claim names it and preferred_username matches it, as the one tenant resolver
     * requires; the hospital claims are not read.
     */
    private String linkedToken(String username, UUID hospitalId, String... roles) {
        com.example.hms.model.User user = accounts.userAt(username, hospitalId, roles);
        // The account is linked, so the idle gate now applies on this path too.
        idleSessionTracker.touch(user.getId());
        return token(user.getUsername(), user.getId(), hospitalId, roles);
    }

    private static String token(String username, UUID appUserId, UUID hospitalId, String... realmRoles) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issuer(OidcResourceServerIntegrationTest.TEST_ISSUER)
            .subject(UUID.randomUUID().toString())
            .audience(List.of(OidcResourceServerIntegrationTest.TEST_AUDIENCE))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("preferred_username", username)
            .claim("typ", "Bearer")
            .claim("azp", "hms-portal")
            .claim("appUserId", appUserId.toString())
            .claim("realm_access", Map.of("roles", List.of(realmRoles)));
        if (hospitalId != null) {
            claims.claim("hospital_id", hospitalId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("b3cds-test").build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
        return jwt.serialize();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As {@code PatientSubjectReadSecurityIT.SignedKeycloakTokens}. */
    @TestConfiguration
    static class SignedKeycloakTokens {

        @Bean
        @Primary
        JwtDecoder oidcJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(OidcResourceServerIntegrationTest.TEST_ISSUER),
                new OidcResourceServerIntegrationTest.TestAudienceValidator(OidcResourceServerIntegrationTest.TEST_AUDIENCE));
            decoder.setJwtValidator(validators);
            return decoder;
        }

        @Bean
        @Primary
        BearerTokenResolver issuerAwareBearerTokenResolver() {
            return new IssuerAwareBearerTokenResolver(OidcResourceServerIntegrationTest.TEST_ISSUER);
        }

        @Bean
        KeycloakHospitalContextFilter keycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                                                   ActingScopeResolver actingScopeResolver,
                                                                   IdleSessionGate idleSessionGate,
                                                                   TenantLifecycleGate tenantLifecycleGate,
                                                                   UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, actingScopeResolver, idleSessionGate,
                tenantLifecycleGate, userRepository);
        }
    }
}
