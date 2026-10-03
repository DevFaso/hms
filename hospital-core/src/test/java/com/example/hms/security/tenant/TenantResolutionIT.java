package com.example.hms.security.tenant;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import com.example.hms.service.HospitalLifecycleStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one tenant resolver end to end (docs/security/tenant-resolution.md §4.3):
 * real HTTP, the real filter chain, real bearer tokens on both auth paths (a
 * password-path HMS JWT and a Keycloak-shaped RS256 JWT through the OAuth2
 * resource server).
 *
 * <p>Hospitals A and A2 share an organisation; B is another. The probe for
 * "which hospital does this request act at" is {@code GET /me/hospital}.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestPostgresConfig.class, TenantResolutionIT.OidcTestConfig.class})
// Its own context (the OIDC stand-ins): closed after the class so the cached
// contexts do not exhaust the test fork.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantResolutionIT {

    static final String TEST_ISSUER = "https://tenant-resolution-it.local/realms/hms";
    private static final String HOSPITAL_HEADER = "X-Hospital-Id";
    private static final String ME_HOSPITAL = "/me/hospital";
    private static final String CSRF = "tenant-resolution-it-csrf";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private HospitalLifecycleStatusService hospitalLifecycleStatusService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private ImagingOrderRepository imagingOrderRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Organization sharedOrganization;
    private Organization otherOrganization;
    private Hospital hospitalA;
    private Hospital hospitalA2;
    private Hospital hospitalB;
    private Role doctor;
    private Role superAdminRole;

    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> assignments = new ArrayList<>();
    private final List<UUID> patients = new ArrayList<>();
    private final List<UUID> registrations = new ArrayList<>();
    private final List<UUID> imagingOrders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sharedOrganization = saveOrganization("Shared");
        otherOrganization = saveOrganization("Other");
        hospitalA = saveHospital("Resolution A", sharedOrganization);
        hospitalA2 = saveHospital("Resolution A2", sharedOrganization);
        hospitalB = saveHospital("Resolution B", otherOrganization);
        doctor = ensureRole("ROLE_DOCTOR", "Doctor");
        superAdminRole = ensureRole("ROLE_SUPER_ADMIN", "Super Admin");
    }

    @AfterEach
    void tearDown() {
        auditEventLogRepository.deleteAllInBatch();
        imagingOrderRepository.deleteAllByIdInBatch(imagingOrders);
        registrationRepository.deleteAllByIdInBatch(registrations);
        patientRepository.deleteAllByIdInBatch(patients);
        assignmentRepository.deleteAllByIdInBatch(assignments);
        userRepository.deleteAllByIdInBatch(users);
        hospitalRepository.deleteAllByIdInBatch(List.of(hospitalA.getId(), hospitalA2.getId(), hospitalB.getId()));
        organizationRepository.deleteAllByIdInBatch(List.of(sharedOrganization.getId(), otherOrganization.getId()));
        hospitalLifecycleStatusService.invalidate();
    }

    // ── password path ────────────────────────────────────────────────

    @Test
    @DisplayName("(a) one hospital: acting at it with no header")
    void soleHospital() {
        User user = saveUser("sole");
        assign(user, doctor, hospitalA, true);
        assertThat(actingAt(legacyToken(user, "ROLE_DOCTOR"), null)).isEqualTo(hospitalA.getId().toString());
    }

    @Test
    @DisplayName("(b) two hospitals: none named is refused, the header or ?hospitalId= is served at B")
    void multiHospital() {
        User user = saveUser("multi");
        assign(user, doctor, hospitalA, true);
        assign(user, doctor, hospitalB, true);
        String token = legacyToken(user, "ROLE_DOCTOR");

        assertThat(get(ME_HOSPITAL, token, null).getStatusCode().value()).as("never the newest").isEqualTo(400);
        assertThat(actingAt(token, hospitalB.getId().toString())).isEqualTo(hospitalB.getId().toString());

        Patient atB = savePatient(hospitalB);
        ResponseEntity<String> byParam = get("/patients?hospitalId=" + hospitalB.getId(), token, null);
        assertThat(byParam.getStatusCode().value()).as(byParam.getBody()).isEqualTo(200);
        assertThat(byParam.getBody()).contains(atB.getId().toString());
    }

    @Test
    @DisplayName("(c) super-admin with an incidental assignment: global view, never pinned to it (D2, D3)")
    void superAdminGlobalView() {
        User root = saveUser("root");
        assign(root, superAdminRole, null, true);
        assign(root, doctor, hospitalA2, true);
        String token = legacyToken(root, "ROLE_SUPER_ADMIN");

        assertThat(get(ME_HOSPITAL, token, null).getStatusCode().value()).as("no acting hospital").isEqualTo(400);
        assertThat(get("/me/patients/" + UUID.randomUUID() + "/snapshot", token, null).getBody())
            .as("per-patient reads ask for a hospital (Q1 B), not 'does not exist'")
            .contains("hospital_scope_refused").contains("GLOBAL_VIEW");
    }

    @Test
    @DisplayName("(d) super-admin naming a hospital acts there; ?hospitalId=B is filtered to B with no global row")
    void superAdminPinned() {
        User root = saveUser("root");
        assign(root, superAdminRole, null, true);
        String token = legacyToken(root, "ROLE_SUPER_ADMIN");
        assertThat(actingAt(token, hospitalB.getId().toString())).isEqualTo(hospitalB.getId().toString());

        Patient atA = savePatient(hospitalA);
        Patient atB = savePatient(hospitalB);
        auditEventLogRepository.deleteAllInBatch();

        ResponseEntity<String> global = get("/patients", token, null);
        assertThat(global.getStatusCode().value()).as(global.getBody()).isEqualTo(200);
        assertThat(global.getBody()).contains(atA.getId().toString()).contains(atB.getId().toString());
        assertThat(globalViewRows()).as("one row for the global-view read").isEqualTo(1);

        ResponseEntity<String> narrowed = get("/patients?hospitalId=" + hospitalB.getId(), token, null);
        assertThat(narrowed.getStatusCode().value()).isEqualTo(200);
        assertThat(narrowed.getBody()).contains(atB.getId().toString()).doesNotContain(atA.getId().toString());
        assertThat(globalViewRows()).as("a narrowed read writes no global row").isEqualTo(1);
    }

    @Test
    @DisplayName("(f) revoked after sign-in: the stale chip is 403 NO_LONGER_PERMITTED, a probe NOT_PERMITTED, both audited")
    void revokedHospital() {
        User user = saveUser("revoked");
        UserRoleHospitalAssignment atA = assign(user, doctor, hospitalA, true);
        String token = legacyToken(user, "ROLE_DOCTOR");
        atA.setActive(false);
        assignmentRepository.save(atA);

        ResponseEntity<String> stale = get(ME_HOSPITAL, token, hospitalA.getId().toString());
        assertThat(stale.getStatusCode().value()).isEqualTo(403);
        assertThat(stale.getBody()).contains("NO_LONGER_PERMITTED")
            .as("the refused hospital is named, so the portal forgets that one")
            .contains("\"hospitalId\":\"" + hospitalA.getId() + "\"");
        ResponseEntity<String> probe = get(ME_HOSPITAL, token, hospitalB.getId().toString());
        assertThat(probe.getStatusCode().value()).isEqualTo(403);
        assertThat(probe.getBody()).contains("\"NOT_PERMITTED\"").doesNotContain("hospitalId");
        assertThat(auditEventLogRepository.findAll()).filteredOn(row -> row.getEventDescription() != null
                && row.getEventDescription().startsWith("Hospital scope refused"))
            .hasSize(2);
    }

    @Test
    @DisplayName("a revoked selection is ignored (and audited) on the scope-establishing paths, refused on data paths")
    void revokedSelectionCanStillReestablishTheScope() {
        User user = saveUser("rechoose");
        UserRoleHospitalAssignment atA = assign(user, doctor, hospitalA, true);
        assign(user, doctor, hospitalB, true);
        String token = legacyToken(user, "ROLE_DOCTOR");
        atA.setActive(false);
        assignmentRepository.save(atA);
        String stale = hospitalA.getId().toString();

        ResponseEntity<String> bootstrap = get("/auth/session/bootstrap", token, stale);
        assertThat(bootstrap.getStatusCode().value()).as(bootstrap.getBody()).isEqualTo(200);
        assertThat(bootstrap.getBody()).as("the scope the portal re-chooses from")
            .contains(hospitalB.getId().toString()).doesNotContain(stale);
        ResponseEntity<String> assignmentsNow = get("/me/assignments", token, stale);
        assertThat(assignmentsNow.getStatusCode().value()).as(assignmentsNow.getBody()).isEqualTo(200);
        ResponseEntity<String> refresh = send(HttpMethod.POST, "/auth/token/refresh", "{}", token, stale);
        assertThat(refresh.getBody()).as("refresh is not a scope refusal").doesNotContain("hospital_scope_refused");
        ResponseEntity<String> logout = send(HttpMethod.POST, "/auth/logout", "{}", token, stale);
        assertThat(logout.getStatusCode().is2xxSuccessful()).as(logout.getBody()).isTrue();

        ResponseEntity<String> data = get(ME_HOSPITAL, legacyToken(user, "ROLE_DOCTOR"), stale);
        assertThat(data.getStatusCode().value()).as("a data path still refuses it").isEqualTo(403);
        assertThat(data.getBody()).contains("NO_LONGER_PERMITTED");
        assertThat(auditEventLogRepository.findAll()).as("the ignored header is still audited (deduplicated hourly)")
            .anyMatch(row -> row.getEventDescription() != null
                && row.getEventDescription().startsWith("Hospital scope refused"));
    }

    @Test
    @DisplayName("Keycloak: a revoked selection does not block the session bootstrap either")
    void keycloakRevokedSelectionCanStillBootstrap() {
        User user = saveUser("kc-rechoose");
        UserRoleHospitalAssignment atA = assign(user, doctor, hospitalA, true);
        assign(user, doctor, hospitalB, true);
        atA.setActive(false);
        assignmentRepository.save(atA);
        idleSessionTracker.touch(user.getId());
        String token = keycloak.mintToken(KeycloakJwtFixture.TokenSpec.defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of("DOCTOR"))
            .linkedTo(user.getId(), user.getUsername()));
        String stale = hospitalA.getId().toString();

        assertThat(get("/me/assignments", token, stale).getStatusCode().value()).isEqualTo(200);
        assertThat(get(ME_HOSPITAL, token, stale).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("a stale link to revoked B while A is selected: 403 names B, not the valid header hospital A")
    void staleLinkNamesTheRefusedHospitalNotTheSelection() {
        User user = saveUser("stale-link");
        assign(user, doctor, hospitalA, true);
        UserRoleHospitalAssignment atB = assign(user, doctor, hospitalB, true);
        String token = legacyToken(user, "ROLE_DOCTOR");
        atB.setActive(false);
        assignmentRepository.save(atB);

        ResponseEntity<String> link = get("/patients?hospitalId=" + hospitalB.getId(), token, hospitalA.getId().toString());
        assertThat(link.getStatusCode().value()).as(link.getBody()).isEqualTo(403);
        assertThat(link.getBody()).contains("NO_LONGER_PERMITTED")
            .contains("\"hospitalId\":\"" + hospitalB.getId() + "\"")
            .doesNotContain(hospitalA.getId().toString());
        assertThat(actingAt(token, hospitalA.getId().toString())).as("A is still valid").isEqualTo(hospitalA.getId().toString());
    }

    @Test
    @DisplayName("a demoted super-admin's token no longer passes a SUPER_ADMIN guard (Q10 A)")
    void demotedSuperAdmin() {
        User root = saveUser("demoted");
        UserRoleHospitalAssignment sa = assign(root, superAdminRole, null, true);
        String token = legacyToken(root, "ROLE_SUPER_ADMIN");
        assertThat(get("/super-admin/summary", token, null).getStatusCode().value()).isEqualTo(200);

        sa.setActive(false);
        assignmentRepository.save(sa);
        assertThat(get("/super-admin/summary", token, null).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("Q6 A: a doctor at A cannot re-status an imaging order at sibling A2 of the same organisation")
    void siblingHospitalIsNotReadable() {
        User user = saveUser("sibling");
        assign(user, doctor, hospitalA, true);
        Patient atA2 = savePatient(hospitalA2);
        UUID order = imagingOrderRepository.save(ImagingOrder.builder()
            .patient(atA2)
            .hospital(hospitalA2)
            .modality(ImagingModality.XRAY)
            .studyType("Chest")
            .orderedAt(LocalDateTime.now())
            .build()).getId();
        imagingOrders.add(order);

        ResponseEntity<String> response = send(HttpMethod.PUT, "/imaging/orders/" + order + "/status",
            "{\"status\":\"SCHEDULED\"}", legacyToken(user, "ROLE_DOCTOR"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(404);
    }

    // ── Keycloak path ────────────────────────────────────────────────

    @Test
    @DisplayName("(g) Keycloak without a local account link: no hospital, and a named one is refused")
    void keycloakUnlinked() {
        String token = keycloak.mintToken(KeycloakJwtFixture.TokenSpec.defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of("DOCTOR"))
            .withHospitalId(hospitalA.getId().toString())
            .withRoleAssignments(List.of("ROLE_DOCTOR@" + hospitalA.getId())));

        assertThat(get(ME_HOSPITAL, token, null).getStatusCode().value()).isEqualTo(400);
        assertThat(get(ME_HOSPITAL, token, hospitalA.getId().toString()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("Keycloak linked: the live assignment, not the claim; a suspended hospital answers 423 (D14)")
    void keycloakLinked() {
        User user = saveUser("kc");
        assign(user, doctor, hospitalA, true);
        idleSessionTracker.touch(user.getId());
        String token = keycloak.mintToken(KeycloakJwtFixture.TokenSpec.defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of("DOCTOR"))
            .withHospitalId(hospitalB.getId().toString())
            .linkedTo(user.getId(), user.getUsername()));

        assertThat(actingAt(token, null)).as("the table's A, not the claim's B").isEqualTo(hospitalA.getId().toString());

        hospitalA.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        hospitalRepository.save(hospitalA);
        hospitalLifecycleStatusService.invalidate();
        assertThat(get(ME_HOSPITAL, token, null).getStatusCode().value()).isEqualTo(423);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String actingAt(String token, String header) {
        ResponseEntity<String> response = get(ME_HOSPITAL, token, header);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        String body = response.getBody();
        int at = body.indexOf("\"id\":\"") + 6;
        return body.substring(at, body.indexOf('"', at));
    }

    private long globalViewRows() {
        return auditEventLogRepository.findAll().stream()
            .filter(row -> row.getEventDescription() != null
                && row.getEventDescription().startsWith("Super-admin global-view request"))
            .count();
    }

    private ResponseEntity<String> get(String path, String bearer, String hospitalHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearer);
        if (hospitalHeader != null) {
            headers.set(HOSPITAL_HEADER, hospitalHeader);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** A write: the double-submit CSRF cookie and header the portal sends. */
    private ResponseEntity<String> send(HttpMethod method, String path, String body, String bearer) {
        return send(method, path, body, bearer, null);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, String bearer,
                                        String hospitalHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (hospitalHeader != null) {
            headers.set(HOSPITAL_HEADER, hospitalHeader);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearer);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + CSRF);
        headers.set("X-XSRF-TOKEN", CSRF);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String legacyToken(User user, String... roles) {
        idleSessionTracker.touch(user.getId());
        return jwtTokenProvider.generateAccessToken(
            new TokenUserDescriptor(user.getId(), user.getUsername(), List.of(roles)));
    }

    private Organization saveOrganization(String name) {
        return organizationRepository.save(Organization.builder()
            .name("Resolution " + name + " " + nextId())
            .code("ORG-TR-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
    }

    private Hospital saveHospital(String name, Organization organization) {
        String n = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + n)
            .code("HTR" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226557" + n)
            .email("tr" + n + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Role ensureRole(String code, String name) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(name)
                .code(code)
                .description(name + " role")
                .build()));
    }

    private User saveUser(String prefix) {
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username(prefix + "-tr-" + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@resolution.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build());
        users.add(user.getId());
        return user;
    }

    private UserRoleHospitalAssignment assign(User user, Role role, Hospital at, boolean active) {
        UserRoleHospitalAssignment saved = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-TR-" + nextId())
            .description(role.getName() + " assignment")
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(active)
            .build());
        assignments.add(saved.getId());
        return saved;
    }

    private Patient savePatient(Hospital hospital) {
        String suffix = nextId();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Resolution" + suffix)
            .dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("F")
            .address("Patient address")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("patient" + suffix + "@resolution.test")
            .hospitalId(hospital.getId())
            .user(saveUser("patient"))
            .build());
        patients.add(saved.getId());
        registrations.add(registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved)
            .hospital(hospital)
            .mrn("MRN-TR-" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build()).getId());
        return saved;
    }

    private static String nextId() {
        return RUN + String.format("%05d", SEQUENCE.incrementAndGet());
    }

    /**
     * Stands in for {@code OidcResourceServerConfig} (which discovers its
     * decoder over HTTP): the fixture's key and the issuer-aware resolver, so
     * Keycloak-shaped tokens take the real OIDC path, and the Keycloak filter
     * wired by the same {@code SecurityConfig} code as in production.
     */
    @TestConfiguration
    static class OidcTestConfig {

        static final String AUDIENCE = "hms-backend";

        @Bean
        KeycloakJwtFixture keycloakJwtFixture() {
            return new KeycloakJwtFixture();
        }

        @Bean
        @Primary
        JwtDecoder oidcJwtDecoder(KeycloakJwtFixture fixture) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(fixture.publicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TEST_ISSUER));
            return decoder;
        }

        @Bean
        @Primary
        BearerTokenResolver issuerAwareBearerTokenResolver() {
            return new IssuerAwareBearerTokenResolver(TEST_ISSUER);
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
