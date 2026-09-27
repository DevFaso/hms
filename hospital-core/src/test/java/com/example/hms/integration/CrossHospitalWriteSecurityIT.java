package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UltrasoundReportRepository;
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
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
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
import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Writes on ultrasound orders and reports, consultations and prescriptions
 * are held to the hospital the caller acts at — through the REAL security
 * filter chain, with a signed Keycloak-style bearer token whose
 * {@code hospital_id} claim makes hospital A the active hospital (decoded by
 * the resource server, converted, run past {@code KeycloakHospitalContextFilter},
 * the URL matchers and the method security, as a portal request is).
 *
 * <p>Each of these writes used to load its row by id and act on it whatever
 * hospital it belonged to. Every refusal here must be indistinguishable from
 * an id that matches no row: the same status, the same message once the id
 * itself is masked, the same path — and nothing is saved.
 *
 * <p>Repositories for the rows under test are mocked: what is under test is
 * which rows the services agree to act on.
 */
@AutoConfigureMockMvc
@Import(CrossHospitalWriteSecurityIT.SignedKeycloakTokens.class)
class CrossHospitalWriteSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();
    private static final String ID = "<id>";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UltrasoundOrderRepository ultrasoundOrderRepository;
    @MockitoBean private UltrasoundReportRepository ultrasoundReportRepository;
    @MockitoBean private ConsultationRepository consultationRepository;
    @MockitoBean private PrescriptionRepository prescriptionRepository;

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;

    private final UUID rowId = UUID.randomUUID();
    /**
     * Real hospitals and a real patient registered at both: the create and move
     * refusals must be seen to be the ACTING-hospital check, not a lookup that
     * happened to miss. With B a real row and the patient registered at A and
     * B, dropping the check lets a create or move at B go through.
     */
    private UUID hospitalA;
    private UUID hospitalB;
    private Patient registeredAtBoth;
    private String doctorAtA;

    @BeforeEach
    void realHospitalsAndPatient() {
        String n = String.format("%05d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000, 100000));
        Hospital a = hospitalRepository.save(hospitalRow("Write Scope A " + n, "WSA" + n, n));
        Hospital b = hospitalRepository.save(hospitalRow("Write Scope B " + n, "WSB" + n, n + "1"));
        hospitalA = a.getId();
        hospitalB = b.getId();
        registeredAtBoth = patientRepository.save(Patient.builder()
            .firstName("Mariam").lastName("Sawadogo").dateOfBirth(java.time.LocalDate.of(1990, 5, 6)).gender("F")
            .address("Somewhere").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("+22673" + n).email("mariam" + n + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("+22674" + n)
            .hospitalId(hospitalA)
            .user(userRepository.save(com.example.hms.model.User.builder()
                .username("ws.patient" + n).passwordHash("hashed").email("ws" + n + "@patient.test")
                .firstName("Mariam").lastName("S" + n).phoneNumber("+22675" + n).isActive(true).build()))
            .build());
        for (Hospital h : List.of(a, b)) {
            registrationRepository.save(PatientHospitalRegistration.builder()
                .patient(registeredAtBoth).hospital(h).mrn("MRN-WS-" + h.getCode())
                .registrationDate(java.time.LocalDate.now()).active(true).build());
        }
        doctorAtA = token("doctor.a", UUID.randomUUID(), hospitalA, "DOCTOR");
    }

    @AfterEach
    void removeRows() {
        registrationRepository.deleteAll(registrationRepository.findByPatientId(registeredAtBoth.getId()));
        com.example.hms.model.User patientUser = registeredAtBoth.getUser();
        patientRepository.delete(registeredAtBoth);
        userRepository.delete(patientUser);
        hospitalRepository.deleteAllById(List.of(hospitalA, hospitalB));
    }

    private static Hospital hospitalRow(String name, String code, String n) {
        return Hospital.builder().name(name).code(code).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+22656" + n).email("h" + n + "@write.test").build();
    }

    // ── ultrasound ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A's doctor cannot edit, cancel or report on B's ultrasound order: each answers as a missing id")
    void ultrasoundOrderWritesAnswerAsMissing() throws Exception {
        String edit = json(Map.of("patientId", UUID.randomUUID(), "hospitalId", hospitalB, "scanType", UltrasoundScanType.GROWTH_SCAN));
        Map<String, Function<UUID, MockHttpServletRequestBuilder>> writes = new LinkedHashMap<>();
        writes.put("update", id -> put("/ultrasound/orders/{id}", id).contentType(MediaType.APPLICATION_JSON).content(edit));
        writes.put("cancel", id -> post("/ultrasound/orders/{id}/cancel", id));
        String report = json(Map.of("scanDate", java.time.LocalDate.now().toString(), "findingCategory", "NORMAL"));
        writes.put("report", id -> post("/ultrasound/orders/{id}/report", id).contentType(MediaType.APPLICATION_JSON).content(report));

        for (Map.Entry<String, Function<UUID, MockHttpServletRequestBuilder>> write : writes.entrySet()) {
            when(ultrasoundOrderRepository.findById(rowId)).thenReturn(Optional.of(ultrasoundOrderAt(hospitalB)));
            MvcResult foreign = send(write.getValue().apply(rowId));
            when(ultrasoundOrderRepository.findById(rowId)).thenReturn(Optional.empty());
            MvcResult missing = send(write.getValue().apply(rowId));
            assertThat(foreign.getResponse().getStatus()).as(write.getKey()).isEqualTo(404);
            assertThat(errorShape(foreign, rowId)).as(write.getKey()).isEqualTo(errorShape(missing, rowId));
        }
        verify(ultrasoundOrderRepository, never()).save(any());
        verify(ultrasoundReportRepository, never()).save(any());
    }

    @Test
    @DisplayName("A's doctor cannot review B's ultrasound report or mark its patient notified: as a missing id")
    void ultrasoundReportWritesAnswerAsMissing() throws Exception {
        for (String action : List.of("review", "notify-patient")) {
            when(ultrasoundReportRepository.findById(rowId)).thenReturn(Optional.of(ultrasoundReportAt(hospitalB)));
            MvcResult foreign = send(post("/ultrasound/reports/{id}/" + action, rowId));
            when(ultrasoundReportRepository.findById(rowId)).thenReturn(Optional.empty());
            MvcResult missing = send(post("/ultrasound/reports/{id}/" + action, rowId));
            assertThat(foreign.getResponse().getStatus()).as(action).isEqualTo(404);
            assertThat(errorShape(foreign, rowId)).as(action).isEqualTo(errorShape(missing, rowId));
        }
        verify(ultrasoundReportRepository, never()).save(any());
    }

    @Test
    @DisplayName("an ultrasound order cannot be created at, or moved to, hospital B: as a missing hospital")
    void ultrasoundOrderNeverLeavesTheActingHospital() throws Exception {
        UUID missingHospital = UUID.randomUUID();
        UUID patientId = registeredAtBoth.getId();
        Function<UUID, String> orderAt = h -> json(Map.of("patientId", patientId, "hospitalId", h,
            "scanType", UltrasoundScanType.GROWTH_SCAN));

        MvcResult createAtB = send(post("/ultrasound/orders").contentType(MediaType.APPLICATION_JSON).content(orderAt.apply(hospitalB)));
        MvcResult createAtMissing = send(post("/ultrasound/orders").contentType(MediaType.APPLICATION_JSON).content(orderAt.apply(missingHospital)));
        assertThat(createAtB.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(createAtB, hospitalB)).isEqualTo(errorShape(createAtMissing, missingHospital));

        when(ultrasoundOrderRepository.findById(rowId)).thenReturn(Optional.of(ultrasoundOrderAt(hospitalA)));
        MvcResult moveToB = send(put("/ultrasound/orders/{id}", rowId).contentType(MediaType.APPLICATION_JSON).content(orderAt.apply(hospitalB)));
        MvcResult moveToMissing = send(put("/ultrasound/orders/{id}", rowId).contentType(MediaType.APPLICATION_JSON).content(orderAt.apply(missingHospital)));
        assertThat(moveToB.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(moveToB, hospitalB)).isEqualTo(errorShape(moveToMissing, missingHospital));
        verify(ultrasoundOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("at its own hospital the doctor still cancels an ultrasound order")
    void ultrasoundCancelAtTheActingHospitalWorks() throws Exception {
        UltrasoundOrder own = ultrasoundOrderAt(hospitalA);
        when(ultrasoundOrderRepository.findById(rowId)).thenReturn(Optional.of(own));
        when(ultrasoundOrderRepository.save(own)).thenReturn(own);

        MvcResult result = send(post("/ultrasound/orders/{id}/cancel", rowId));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(own.getStatus()).isEqualTo(UltrasoundOrderStatus.CANCELLED);
    }

    // ── consultations ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A's doctor cannot act on B's consultation: every write answers as a missing id")
    void consultationWritesAnswerAsMissing() throws Exception {
        String complete = json(Map.of("recommendations", "Rest"));
        String decline = json(Map.of("declineReason", "Not my specialty"));
        String cancel = json(Map.of("cancellationReason", "No longer needed"));
        String schedule = json(Map.of("scheduledAt", LocalDateTime.now().plusDays(1).withNano(0).toString()));
        Map<String, Function<UUID, MockHttpServletRequestBuilder>> writes = new LinkedHashMap<>();
        writes.put("acknowledge", id -> post("/consultations/{id}/acknowledge", id));
        String update = json(Map.of("consultantId", UUID.randomUUID()));
        writes.put("update", id -> put("/consultations/{id}", id).contentType(MediaType.APPLICATION_JSON).content(update));
        writes.put("schedule", id -> post("/consultations/{id}/schedule", id).contentType(MediaType.APPLICATION_JSON).content(schedule));
        writes.put("start", id -> post("/consultations/{id}/start", id));
        writes.put("complete", id -> post("/consultations/{id}/complete", id).contentType(MediaType.APPLICATION_JSON).content(complete));
        writes.put("decline", id -> post("/consultations/{id}/decline", id).contentType(MediaType.APPLICATION_JSON).content(decline));
        writes.put("cancel", id -> post("/consultations/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON).content(cancel));

        for (Map.Entry<String, Function<UUID, MockHttpServletRequestBuilder>> write : writes.entrySet()) {
            when(consultationRepository.findById(rowId)).thenReturn(Optional.of(consultationAt(hospitalB, ConsultationStatus.ASSIGNED)));
            MvcResult foreign = send(write.getValue().apply(rowId));
            when(consultationRepository.findById(rowId)).thenReturn(Optional.empty());
            MvcResult missing = send(write.getValue().apply(rowId));
            assertThat(foreign.getResponse().getStatus()).as(write.getKey()).isEqualTo(404);
            assertThat(errorShape(foreign, rowId)).as(write.getKey()).isEqualTo(errorShape(missing, rowId));
        }
        verify(consultationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a consultation cannot be requested at hospital B: as a missing hospital")
    void consultationCannotBeRequestedAtAnotherHospital() throws Exception {
        UUID missingHospital = UUID.randomUUID();
        UUID patientId = registeredAtBoth.getId();
        Function<UUID, String> requestAt = h -> json(Map.of("patientId", patientId, "hospitalId", h,
            "consultationType", ConsultationType.INPATIENT_CONSULT, "specialtyRequested", "Cardiology",
            "reasonForConsult", "Chest pain", "urgency", ConsultationUrgency.ROUTINE));

        MvcResult atB = send(post("/consultations").contentType(MediaType.APPLICATION_JSON).content(requestAt.apply(hospitalB)));
        MvcResult atMissing = send(post("/consultations").contentType(MediaType.APPLICATION_JSON).content(requestAt.apply(missingHospital)));

        assertThat(atB.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(atB, hospitalB)).isEqualTo(errorShape(atMissing, missingHospital));
        verify(consultationRepository, never()).save(any());
    }

    @Test
    @DisplayName("at its own hospital the doctor still starts an assigned consultation")
    void consultationStartAtTheActingHospitalWorks() throws Exception {
        Consultation own = consultationAt(hospitalA, ConsultationStatus.ASSIGNED);
        when(consultationRepository.findById(rowId)).thenReturn(Optional.of(own));
        when(consultationRepository.save(own)).thenReturn(own);

        MvcResult result = send(post("/consultations/{id}/start", rowId));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(own.getStatus()).isEqualTo(ConsultationStatus.IN_PROGRESS);
    }

    // ── prescriptions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A's doctor cannot rewrite B's prescription: answered as a missing id")
    void prescriptionUpdateAnswersAsMissing() throws Exception {
        String body = json(Map.of("patientId", UUID.randomUUID(), "medicationName", "Amoxicillin"));
        Prescription foreignRow = new Prescription();
        foreignRow.setId(rowId);
        foreignRow.setHospital(hospital(hospitalB));

        when(prescriptionRepository.findById(rowId)).thenReturn(Optional.of(foreignRow));
        MvcResult foreign = send(put("/prescriptions/{id}", rowId).contentType(MediaType.APPLICATION_JSON).content(body));
        when(prescriptionRepository.findById(rowId)).thenReturn(Optional.empty());
        MvcResult missing = send(put("/prescriptions/{id}", rowId).contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(foreign, rowId)).isEqualTo(errorShape(missing, rowId));
        verify(prescriptionRepository, never()).save(any());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private MvcResult send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(csrf()).header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorAtA)).andReturn();
    }

    /** Status, message (with the given id masked) and path — everything but the timestamp. */
    private String errorShape(MvcResult result, UUID maskedId) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return result.getResponse().getStatus()
            + "|" + body.path("message").asText().replace(maskedId.toString(), ID)
            + "|" + body.path("path").asText().replace(maskedId.toString(), ID);
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private static Patient patient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        return patient;
    }

    private UltrasoundOrder ultrasoundOrderAt(UUID hospitalId) {
        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(rowId);
        order.setPatient(patient());
        order.setHospital(hospital(hospitalId));
        order.setStatus(UltrasoundOrderStatus.ORDERED);
        order.setScanType(UltrasoundScanType.GROWTH_SCAN);
        return order;
    }

    private UltrasoundReport ultrasoundReportAt(UUID hospitalId) {
        UltrasoundReport report = new UltrasoundReport();
        report.setId(rowId);
        report.setHospital(hospital(hospitalId));
        report.setUltrasoundOrder(ultrasoundOrderAt(hospitalId));
        return report;
    }

    private Consultation consultationAt(UUID hospitalId, ConsultationStatus status) {
        Consultation consultation = Consultation.builder()
            .hospital(hospital(hospitalId))
            .patient(patient())
            .status(status)
            .consultationType(ConsultationType.INPATIENT_CONSULT)
            .urgency(ConsultationUrgency.ROUTINE)
            .specialtyRequested("Cardiology")
            .requestedAt(LocalDateTime.now())
            .build();
        consultation.setId(rowId);
        return consultation;
    }

    // ── token minting (the shape PatientSubjectReadSecurityIT uses) ────────

    private static String token(String username, UUID appUserId, UUID hospitalId, String... realmRoles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issuer(OidcResourceServerIntegrationTest.TEST_ISSUER)
            .subject(UUID.randomUUID().toString())
            .audience(List.of(OidcResourceServerIntegrationTest.TEST_AUDIENCE))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)))
            .claim("preferred_username", username)
            .claim("typ", "Bearer")
            .claim("azp", "hms-portal")
            .claim("appUserId", appUserId.toString())
            .claim("hospital_id", hospitalId.toString())
            .claim("realm_access", Map.of("roles", List.of(realmRoles)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("p2-writes").build(), claims);
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
