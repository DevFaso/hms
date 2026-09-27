package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.model.BirthPlan;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import com.example.hms.repository.BirthPlanRepository;
import com.example.hms.repository.HighRiskPregnancyCarePlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The existence oracles, through the REAL security filter chain with a signed
 * Keycloak-style patient token ({@code appUserId} claim, a subject that is NOT
 * the user id, {@code hospital_id} naming the patient's hospital).
 *
 * <p>Each of these answered a real row belonging to another patient
 * differently from an id that matches nothing — 403 (birth plan, insurance,
 * booking, portal refill) or 400 (high-risk plan) beside a 404 — so a patient
 * could learn which ids exist. Each now answers the foreign row exactly as the
 * missing one: same status, same message once the id is masked, same path.
 * PatientInsurance also used to refuse a Keycloak patient their OWN insurance
 * ({@code RoleValidator.getCurrentUserId()} is null on this token).
 *
 * <p>People (users, patients, registrations) are real rows so the ownership
 * checks and the hospital filter run for real; the clinical rows are mocked
 * repositories, since what is under test is which rows the services agree to
 * hand out.
 */
@AutoConfigureMockMvc
@Import(PatientExistenceOracleSecurityIT.SignedKeycloakTokens.class)
class PatientExistenceOracleSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();
    private static final String ID = "<id>";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;

    @MockitoBean private BirthPlanRepository birthPlanRepository;
    @MockitoBean private HighRiskPregnancyCarePlanRepository carePlanRepository;
    @MockitoBean private PatientInsuranceRepository insuranceRepository;
    @MockitoBean private RefillRequestRepository refillRequestRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Hospital hospital;
    private User callerUser;
    private Patient caller;
    private Patient stranger;
    private String token;

    @BeforeEach
    void setUp() {
        String n = next();
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Oracle Hospital " + n).code("ORC" + n).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+22655" + n).email("h" + n + "@oracle.test").build());
        Role patientRole = roleRepository.findByCode("ROLE_PATIENT")
            .orElseGet(() -> roleRepository.save(Role.builder().name("PATIENT").code("ROLE_PATIENT").description("patient").build()));
        callerUser = patientUser(patientRole);
        caller = patient(callerUser);
        stranger = patient(patientUser(patientRole));
        token = token(callerUser.getUsername(), callerUser.getId(), hospital.getId());
    }

    @AfterEach
    void tearDown() {
        registrationRepository.deleteAll(registrationRepository.findByPatientId(caller.getId()));
        registrationRepository.deleteAll(registrationRepository.findByPatientId(stranger.getId()));
        User strangerUser = stranger.getUser();
        patientRepository.deleteAll(List.of(caller, stranger));
        userRepository.deleteAll(List.of(callerUser, strangerUser));
        hospitalRepository.delete(hospital);
    }

    // ── birth plans ────────────────────────────────────────────────────────

    @Test
    @DisplayName("another patient's birth plan, and their patient id, answer exactly as missing ones")
    void birthPlans() throws Exception {
        UUID planId = UUID.randomUUID();
        BirthPlan foreign = new BirthPlan();
        foreign.setId(planId);
        foreign.setPatient(stranger);
        when(birthPlanRepository.findById(planId)).thenReturn(Optional.of(foreign));
        MvcResult refused = send(get("/birth-plans/{id}", planId));
        when(birthPlanRepository.findById(planId)).thenReturn(Optional.empty());
        MvcResult missing = send(get("/birth-plans/{id}", planId));
        assertSame(refused, missing, planId, planId);

        UUID unknown = UUID.randomUUID();
        assertSame(send(get("/birth-plans/patient/{pid}", stranger.getId())),
            send(get("/birth-plans/patient/{pid}", unknown)), stranger.getId(), unknown);
        verify(birthPlanRepository, never()).findByPatientIdOrderByCreatedAtDesc(any());
    }

    // ── high-risk care plans ───────────────────────────────────────────────

    @Test
    @DisplayName("another patient's high-risk care plan answers exactly as a missing one (it was a 400)")
    void highRiskPlans() throws Exception {
        UUID planId = UUID.randomUUID();
        HighRiskPregnancyCarePlan foreign = new HighRiskPregnancyCarePlan();
        foreign.setId(planId);
        foreign.setPatient(stranger);
        when(carePlanRepository.findById(planId)).thenReturn(Optional.of(foreign));
        MvcResult refused = send(get("/high-risk-care-plans/{id}", planId));
        when(carePlanRepository.findById(planId)).thenReturn(Optional.empty());
        MvcResult missing = send(get("/high-risk-care-plans/{id}", planId));
        assertSame(refused, missing, planId, planId);
    }

    // ── patient insurance (and item 4: a Keycloak patient reads their own) ──

    @Test
    @DisplayName("a Keycloak patient lists their own insurance; another patient's id answers as an unknown one")
    void insurance() throws Exception {
        when(insuranceRepository.findByPatient_Id(caller.getId())).thenReturn(List.of(insuranceOf(caller)));
        MvcResult own = send(get("/patient-insurances/patient/{pid}", caller.getId()));
        assertThat(own.getResponse().getStatus()).as(own.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(objectMapper.readTree(own.getResponse().getContentAsString())).hasSize(1);

        UUID unknown = UUID.randomUUID();
        assertSame(send(get("/patient-insurances/patient/{pid}", stranger.getId())),
            send(get("/patient-insurances/patient/{pid}", unknown)), stranger.getId(), unknown);
        verify(insuranceRepository, never()).findByPatient_Id(stranger.getId());

        UUID insuranceId = UUID.randomUUID();
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.of(insuranceOf(stranger)));
        MvcResult refused = send(get("/patient-insurances/{id}", insuranceId));
        when(insuranceRepository.findById(insuranceId)).thenReturn(Optional.empty());
        assertSame(refused, send(get("/patient-insurances/{id}", insuranceId)), insuranceId, insuranceId);
    }

    // ── booking ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking for another patient answers exactly as booking for an unknown one (it was a 403)")
    void booking() throws Exception {
        UUID unknown = UUID.randomUUID();
        MvcResult forStranger = send(post("/appointments").contentType(MediaType.APPLICATION_JSON)
            .content(booking(stranger.getId())));
        MvcResult forUnknown = send(post("/appointments").contentType(MediaType.APPLICATION_JSON)
            .content(booking(unknown)));
        assertThat(forStranger.getResponse().getStatus()).isEqualTo(404);
        assertSame(forStranger, forUnknown, stranger.getId(), unknown);
    }

    // ── /me/patient ────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelling another patient's refill request answers exactly as a missing one")
    void portalRefill() throws Exception {
        UUID refillId = UUID.randomUUID();
        RefillRequest foreign = new RefillRequest();
        foreign.setId(refillId);
        foreign.setPatient(stranger);
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.of(foreign));
        MvcResult refused = send(put("/me/patient/refills/{id}/cancel", refillId));
        when(refillRequestRepository.findById(refillId)).thenReturn(Optional.empty());
        MvcResult missing = send(put("/me/patient/refills/{id}/cancel", refillId));
        assertSame(refused, missing, refillId, refillId);
        verify(refillRequestRepository, never()).save(any());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(csrf()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /** 404 both, and the same message and path once each id is masked. */
    private void assertSame(MvcResult refused, MvcResult missing, UUID refusedId, UUID missingId) throws Exception {
        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isEqualTo(404);
        assertThat(shape(refused, refusedId)).isEqualTo(shape(missing, missingId));
    }

    private String shape(MvcResult result, UUID maskedId) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return result.getResponse().getStatus()
            + "|" + body.path("message").asText().replace(maskedId.toString(), ID)
            + "|" + body.path("path").asText().replace(maskedId.toString(), ID);
    }

    private String booking(UUID patientId) {
        return objectMapper.writeValueAsString(Map.of(
            "patientId", patientId,
            "hospitalId", hospital.getId(),
            "appointmentDate", LocalDate.now().plusDays(3).toString(),
            "startTime", "10:00:00"));
    }

    private static PatientInsurance insuranceOf(Patient patient) {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(UUID.randomUUID());
        insurance.setPatient(patient);
        insurance.setPayerCode("AETNA");
        insurance.setPolicyNumber("POL-1");
        return insurance;
    }

    private User patientUser(Role patientRole) {
        String n = next();
        User user = userRepository.save(User.builder()
            .username("oracle.patient" + n).passwordHash("hashed").email("oracle" + n + "@patient.test")
            .firstName("Oracle").lastName("P" + n).phoneNumber("+22670" + n).isActive(true).build());
        user.addRole(patientRole);
        return userRepository.save(user);
    }

    private Patient patient(User user) {
        String n = next();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Awa").lastName("Traore").dateOfBirth(LocalDate.of(1994, 2, 3)).gender("F")
            .address("Somewhere").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("+22671" + n).email("awa" + n + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("+22672" + n)
            .hospitalId(hospital.getId()).user(user).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved).hospital(hospital).mrn("MRN-ORC-" + n).registrationDate(LocalDate.now()).active(true).build());
        return saved;
    }

    private String next() {
        return String.format("%05d", sequence.incrementAndGet() + (int) (Math.random() * 90000));
    }

    private static String token(String username, UUID appUserId, UUID hospitalId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issuer(OidcResourceServerIntegrationTest.TEST_ISSUER)
            .subject(UUID.randomUUID().toString())
            .audience(List.of(OidcResourceServerIntegrationTest.TEST_AUDIENCE))
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
            .claim("preferred_username", username)
            .claim("typ", "Bearer")
            .claim("azp", "hms-portal")
            .claim("appUserId", appUserId.toString())
            .claim("hospital_id", hospitalId.toString())
            .claim("realm_access", Map.of("roles", List.of("PATIENT")))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("p2-oracles").build(), claims);
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

    /** The decoder, resolver and hospital-context filter PatientSubjectReadSecurityIT wires, over this class's key. */
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
                                                                   IdleSessionGate idleSessionGate,
                                                                   UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);
        }
    }
}
