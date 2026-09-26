package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemSeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.oidc.KeycloakJwtFixture;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FHIR tenant boundary end to end: real HTTP into the real HAPI servlet,
 * through the real security filter chain, with real bearer tokens on both
 * authentication paths (a legacy HMS JWT and a Keycloak-shaped RS256 JWT).
 *
 * <p>Two hospitals share one patient (P, registered at both); a second
 * patient (Q) is registered at B only. Each of Encounter, Condition,
 * MedicationRequest and Immunization has one row at A and one at B.
 *
 * <p>A doctor at A must read and search A's rows only; B's rows must answer
 * exactly like ids that do not exist.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestPostgresConfig.class, FhirTenantBoundaryIT.OidcTestConfig.class})
// Its own context (the OIDC stand-ins): closed after the class so the cached
// contexts do not exhaust the 2 GB test fork (OutOfMemoryError in a later
// class of the same fork otherwise).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FhirTenantBoundaryIT {

    static final String TEST_ISSUER = "https://fhir-boundary-it.local/realms/hms";
    private static final String FHIR_JSON = "application/fhir+json";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String HOSPITAL_HEADER = "X-Hospital-Id";

    /** JUnit builds one instance per test, so the counter restarts; the run prefix keeps codes unique. */
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private FhirTenantBoundary boundary;
    @Autowired private FhirContext fhirContext;
    @Autowired private FhirWriteProperties writeProperties;
    @Autowired private List<IResourceProvider> providers;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PatientProblemRepository problemRepository;
    @Autowired private ImmunizationRepository immunizationRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User doctorA;
    private User dualRoleUser;
    private User orphan;
    private User superAdmin;
    private Patient patientP;
    private Patient patientQ;
    private String mrnOfPAtA;
    private String mrnOfPAtB;
    private final Rows atA = new Rows();
    private final Rows atB = new Rows();

    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> assignments = new ArrayList<>();
    private final List<UUID> staff = new ArrayList<>();
    private final List<UUID> registrations = new ArrayList<>();
    private final List<UUID> patients = new ArrayList<>();

    /**
     * One row of each leaking type at one hospital, for patient P; and the
     * patient each hospital's Patient row stands for (P, registered at both,
     * for A; Q, registered at B only, for B).
     */
    private static final class Rows {
        UUID patient;
        UUID encounter;
        UUID condition;
        UUID immunization;
        UUID prescription;

        String idOf(String type) {
            return switch (type) {
                case "Patient" -> patient.toString();
                case "Encounter" -> encounter.toString();
                case "Condition" -> condition.toString();
                case "Immunization" -> immunization.toString();
                case "MedicationRequest" -> prescription.toString();
                default -> throw new IllegalArgumentException(type);
            };
        }
    }

    private static final List<String> TYPES =
        List.of("Patient", "Encounter", "Condition", "MedicationRequest", "Immunization");

    /** A search of {@code type} by patient: {@code _id} for Patient itself, {@code patient} for the rest. */
    private static String byPatient(String type, Object patientId) {
        return "/fhir/" + type + ("Patient".equals(type) ? "?_id=" : "?patient=") + patientId;
    }

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("FHIR Boundary Network " + nextId())
            .code("ORG-FB-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospitalA = saveHospital("Boundary Hospital A");
        hospitalB = saveHospital("Boundary Hospital B");

        Role doctor = ensureRole(ROLE_DOCTOR, "Doctor");
        doctorA = saveUser("docA");
        UserRoleHospitalAssignment assignmentA = saveAssignment(doctorA, doctor, hospitalA);
        Staff staffA = saveStaff(doctorA, hospitalA, assignmentA);
        User doctorB = saveUser("docB");
        UserRoleHospitalAssignment assignmentB = saveAssignment(doctorB, doctor, hospitalB);
        Staff staffB = saveStaff(doctorB, hospitalB, assignmentB);

        // DOCTOR at A, RECEPTIONIST at B: the union of the two passes any path matcher.
        dualRoleUser = saveUser("dual");
        saveAssignment(dualRoleUser, doctor, hospitalA);
        saveAssignment(dualRoleUser, ensureRole("ROLE_RECEPTIONIST", "Receptionist"), hospitalB);

        // Holds a doctor token but no assignment at all — the principal
        // HospitalContextRequestOverrides lets pick ANY hospital by header.
        orphan = saveUser("orphan");
        superAdmin = saveUser("root");

        patientP = savePatient(hospitalA);
        mrnOfPAtA = register(patientP, hospitalA);
        mrnOfPAtB = register(patientP, hospitalB);
        patientQ = savePatient(hospitalB);
        register(patientQ, hospitalB);

        atA.patient = patientP.getId();
        atB.patient = patientQ.getId();
        seedRows(atA, hospitalA, staffA, assignmentA);
        seedRows(atB, hospitalB, staffB, assignmentB);
    }

    @AfterEach
    void tearDown() {
        prescriptionRepository.deleteAllByIdInBatch(present(atA.prescription, atB.prescription));
        immunizationRepository.deleteAllByIdInBatch(present(atA.immunization, atB.immunization));
        problemRepository.deleteAllByIdInBatch(present(atA.condition, atB.condition));
        encounterRepository.deleteAllByIdInBatch(present(atA.encounter, atB.encounter));
        registrationRepository.deleteAllByIdInBatch(registrations);
        patientRepository.deleteAllByIdInBatch(patients);
        staffRepository.deleteAllByIdInBatch(staff);
        assignmentRepository.deleteAllByIdInBatch(assignments);
        userRepository.deleteAllByIdInBatch(users);
        hospitalRepository.deleteAllByIdInBatch(present(
            hospitalA == null ? null : hospitalA.getId(), hospitalB == null ? null : hospitalB.getId()));
        organizationRepository.deleteAllByIdInBatch(present(organization == null ? null : organization.getId()));
    }

    private static List<UUID> present(UUID... ids) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("an in-tenant read works on every provider the boundary covers")
    void inTenantReadsWork() {
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        for (String type : TYPES) {
            ResponseEntity<String> response = get("/fhir/" + type + "/" + atA.idOf(type), token, null);
            assertThat(response.getStatusCode().value()).as(type).isEqualTo(200);
            assertThat(response.getBody()).as(type).contains(atA.idOf(type));
        }
    }

    @Test
    @DisplayName("an in-tenant Patient read and search answer 200 with this hospital's MRN and no other's")
    void inTenantPatientReadAndSearchWork() {
        // PatientFhirMapper walks the LAZY hospitalRegistrations; with
        // open-in-view off, mapping outside a transaction was a 500 for every
        // caller on every Patient read and search. P is registered at A and B:
        // a reader bound to A must not learn B's MRN, nor that B holds P.
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        String id = patientP.getId().toString();

        ResponseEntity<String> read = get("/fhir/Patient/" + id, token, null);
        assertThat(read.getStatusCode().value()).as(read.getBody()).isEqualTo(200);
        assertOnlyTheMrnAtA(read.getBody(), "read");

        for (String query : List.of("_id=" + id, "name=" + patientP.getLastName(), "identifier=" + mrnOfPAtA)) {
            ResponseEntity<String> search = get("/fhir/Patient?" + query, token, null);
            assertThat(entryIds(json(search))).as(query).containsExactly(id);
            assertOnlyTheMrnAtA(search.getBody(), query);
        }
    }

    @Test
    @DisplayName("a Patient PUT and conditional create answer with the resource, not a 500 after the commit")
    void patientWritesAnswerWithTheResource() {
        // The write flag is read on every request; flipped here rather than in
        // a context of its own, and put back whatever happens.
        boolean wasEnabled = writeProperties.isEnabled();
        writeProperties.setEnabled(true);
        try {
            String token = legacyToken(doctorA, ROLE_DOCTOR);
            String id = patientP.getId().toString();
            String mrnSystem = "urn:hms:hospital:" + hospitalA.getId() + ":mrn";

            // Nothing to change: the patient is still an uninitialised proxy
            // (it comes from registration.getPatient()) when the answer is mapped.
            String unchanged = "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\"}";
            ResponseEntity<String> noOp = send(HttpMethod.PUT, "/fhir/Patient/" + id, unchanged, token, null);
            assertThat(noOp.getStatusCode().value()).as(noOp.getBody()).isEqualTo(200);
            assertOnlyTheMrnAtA(noOp.getBody(), "no-op PUT");

            String newPhone = "+22670" + nextId().substring(6);
            String newEmail = "Changed." + nextId() + "@Boundary.Test";
            String changed = "{\"resourceType\":\"Patient\",\"id\":\"" + id + "\",\"telecom\":["
                + "{\"system\":\"phone\",\"use\":\"mobile\",\"value\":\"" + newPhone + "\"},"
                + "{\"system\":\"email\",\"value\":\"" + newEmail + "\"}]}";
            ResponseEntity<String> update = send(HttpMethod.PUT, "/fhir/Patient/" + id, changed, token, null);
            assertThat(update.getStatusCode().value()).as(update.getBody()).isEqualTo(200);
            assertThat(update.getBody()).contains(newPhone);
            // What the row holds, not what was sent: the entity lower-cases the email when it is flushed.
            assertThat(update.getBody()).contains(newEmail.toLowerCase()).doesNotContain(newEmail);
            assertOnlyTheMrnAtA(update.getBody(), "PUT");

            String body = "{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + mrnSystem
                + "\",\"value\":\"" + mrnOfPAtA + "\"}]}";
            ResponseEntity<String> conditional = send(HttpMethod.POST, "/fhir/Patient", body, token,
                "identifier=" + mrnSystem + "|" + mrnOfPAtA);
            assertThat(conditional.getStatusCode().value()).as(conditional.getBody()).isEqualTo(200);
            assertThat(objectMapper.readTree(conditional.getBody()).get("id").asText()).isEqualTo(id);
            assertOnlyTheMrnAtA(conditional.getBody(), "conditional create");
        } finally {
            writeProperties.setEnabled(wasEnabled);
        }
    }

    @Test
    @DisplayName("another hospital's row answers exactly like an id that does not exist")
    void crossTenantReadIsIndistinguishableFromMissing() {
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        for (String type : TYPES) {
            assertIndistinguishable(type, atB.idOf(type), token, null);
        }
    }

    // --------------------------------------------------------------- searches

    @Test
    @DisplayName("a search by a patient both hospitals share returns this hospital's rows only, with an exact total")
    void searchIsBoundedToTheHospital() {
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        for (String type : TYPES) {
            JsonNode bundle = json(get(byPatient(type, patientP.getId()), token, null));
            assertThat(entryIds(bundle)).as(type).containsExactly(atA.idOf(type));
            assertThat(bundle.get("total").asInt()).as(type).isEqualTo(1);

            // _count/_offset/_summary=count cannot bring the other hospital's count back.
            JsonNode counted = json(get(byPatient(type, patientP.getId())
                + "&_count=1&_offset=0&_summary=count", token, null));
            assertThat(counted.get("total").asInt()).as("%s _summary=count", type).isEqualTo(1);

            // A page of one is not honoured: the whole in-tenant result comes back.
            JsonNode paged = json(get(byPatient(type, patientP.getId()) + "&_count=1", token, null));
            assertThat(entryIds(paged)).as("%s _count=1", type).containsExactly(atA.idOf(type));
            assertThat(paged.get("total").asInt()).as("%s _count=1 total", type).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a search for a patient known only elsewhere answers like a search for nobody")
    void foreignPatientSearchIsIndistinguishableFromUnknown() {
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        String unknown = UUID.randomUUID().toString();
        for (String type : TYPES) {
            // Patient searches by _id: a patient registered elsewhere used to be a 500 there.
            ResponseEntity<String> foreign = get(byPatient(type, patientQ.getId()), token, null);
            ResponseEntity<String> nobody = get(byPatient(type, unknown), token, null);
            assertThat(foreign.getStatusCode().value()).as(type).isEqualTo(200);
            assertThat(foreign.getStatusCode()).as(type).isEqualTo(nobody.getStatusCode());
            assertThat(normalisedBundle(foreign, patientQ.getId().toString()))
                .as(type).isEqualTo(normalisedBundle(nobody, unknown));
        }
    }

    // ------------------------------------------------------------ the rules

    @Test
    @DisplayName("every provider's resource type is taught to the boundary")
    void everyProviderIsCovered() {
        assertThat(providers).isNotEmpty();
        for (IResourceProvider provider : providers) {
            String type = fhirContext.getResourceType(provider.getResourceType());
            assertThat(FhirTenantBoundary.KNOWN_TYPES).as("%s is refused everything until taught", type)
                .contains(type);
        }
    }

    @Test
    @DisplayName("every rule's query runs, and answers only for its own hospital")
    void everyRuleQueryRuns() {
        String bare = UUID.randomUUID().toString();
        List<String> probes = List.of(bare,
            "labresult-" + bare, "vital-" + bare + "-heart-rate", "laborder-" + bare, "micro-" + bare,
            "imgreport-" + bare, "imgorder-" + bare, "upl-" + bare, "discharge-" + bare,
            "not-a-uuid", "vital-short");
        for (String type : FhirTenantBoundary.KNOWN_TYPES) {
            assertThat(boundary.visibleIdParts(type, probes, hospitalA.getId())).as(type).isEmpty();
        }
        assertThat(boundary.visibleIdParts("Encounter",
            List.of(atA.encounter.toString(), atB.encounter.toString()), hospitalA.getId()))
            .containsExactly(atA.encounter.toString());
        assertThat(boundary.visibleIdParts("Patient",
            List.of(patientP.getId().toString(), patientQ.getId().toString()), hospitalA.getId()))
            .containsExactly(patientP.getId().toString());
        assertThat(boundary.visibleIdParts("Bundle", List.of(bare), hospitalA.getId())).isEmpty();
    }

    // ---------------------------------------------------------- the principal

    @Test
    @DisplayName("X-Hospital-Id cannot choose a hospital the principal does not hold")
    void headerCannotChooseAForeignHospital() {
        // No permitted hospital at all: the override accepts the header, the boundary does not.
        String orphanToken = legacyToken(orphan, ROLE_DOCTOR);
        String hospitalB = this.hospitalB.getId().toString();
        assertThat(get("/fhir/Encounter/" + atB.encounter, orphanToken, hospitalB).getStatusCode().value())
            .isEqualTo(403);
        assertThat(get("/fhir/Encounter?patient=" + patientQ.getId(), orphanToken, hospitalB)
            .getStatusCode().value()).isEqualTo(403);

        // A doctor at A naming B stays at A.
        String token = legacyToken(doctorA, ROLE_DOCTOR);
        assertThat(get("/fhir/Encounter/" + atB.encounter, token, hospitalB).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/fhir/Encounter/" + atA.encounter, token, hospitalB).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("the role must be held at the hospital acted in, not merely somewhere")
    void roleIsCheckedAtTheBoundHospital() {
        String token = legacyToken(dualRoleUser, ROLE_DOCTOR, "ROLE_RECEPTIONIST");
        String atHospitalB = hospitalB.getId().toString();
        assertThat(get("/fhir/Encounter/" + atB.encounter, token, atHospitalB).getStatusCode().value())
            .isEqualTo(403);
        assertThat(get("/fhir/Encounter?patient=" + patientP.getId(), token, atHospitalB).getStatusCode().value())
            .isEqualTo(403);
        String atHospitalA = hospitalA.getId().toString();
        assertThat(get("/fhir/Encounter/" + atA.encounter, token, atHospitalA).getStatusCode().value())
            .isEqualTo(200);

        String keycloakAtB = keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of(ROLE_DOCTOR, "ROLE_RECEPTIONIST"))
            .withRoleAssignments(List.of(ROLE_DOCTOR + "@" + hospitalA.getId(), "ROLE_RECEPTIONIST@" + hospitalB.getId()))
            .withHospitalId(atHospitalB));
        assertThat(get("/fhir/Encounter/" + atB.encounter, keycloakAtB, null).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("a super-admin must pin a hospital, and then sees that hospital only")
    void superAdminMustPin() {
        String token = legacyToken(superAdmin, ROLE_SUPER_ADMIN);
        assertThat(get("/fhir/Encounter/" + atB.encounter, token, null).getStatusCode().value()).isEqualTo(403);
        String pinnedToB = hospitalB.getId().toString();
        assertThat(get("/fhir/Encounter/" + atB.encounter, token, pinnedToB).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/fhir/Encounter/" + atA.encounter, token, pinnedToB).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a Keycloak principal is bounded by its hospital claim, and refused without one")
    void keycloakPrincipalIsBounded() {
        String token = keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of(ROLE_DOCTOR))
            .withRoleAssignments(List.of(ROLE_DOCTOR + "@" + hospitalA.getId()))
            .withHospitalId(hospitalA.getId().toString()));
        ResponseEntity<String> own = get("/fhir/Encounter/" + atA.encounter, token, null);
        assertThat(own.getStatusCode().value()).as(own.getBody()).isEqualTo(200);
        assertIndistinguishable("Encounter", atB.encounter.toString(), token, null);
        JsonNode bundle = json(get("/fhir/Encounter?patient=" + patientP.getId(), token, null));
        assertThat(entryIds(bundle)).containsExactly(atA.encounter.toString());

        String unscoped = keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of(ROLE_DOCTOR)));
        assertThat(get("/fhir/Encounter/" + atB.encounter, unscoped, hospitalB.getId().toString())
            .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("metadata stays public and unbounded")
    void metadataIsUnbounded() {
        assertThat(get("/fhir/metadata", null, null).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

    private void assertIndistinguishable(String type, String foreignId, String token, String hospitalHeader) {
        String missingId = UUID.randomUUID().toString();
        ResponseEntity<String> foreign = get("/fhir/" + type + "/" + foreignId, token, hospitalHeader);
        ResponseEntity<String> missing = get("/fhir/" + type + "/" + missingId, token, hospitalHeader);
        assertThat(foreign.getStatusCode().value()).as("%s foreign", type).isEqualTo(404);
        assertThat(missing.getStatusCode().value()).as("%s missing", type).isEqualTo(404);
        assertThat(foreign.getHeaders().getContentType()).as(type).isEqualTo(missing.getHeaders().getContentType());
        // Byte-identical once the id each request itself named is factored out.
        assertThat(foreign.getBody().replace(foreignId, "{id}"))
            .as("%s body", type).isEqualTo(missing.getBody().replace(missingId, "{id}"));
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, String bearer,
                                        String ifNoneExist) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, FHIR_JSON);
        headers.set(HttpHeaders.CONTENT_TYPE, FHIR_JSON);
        headers.setBearerAuth(bearer);
        if (ifNoneExist != null) {
            headers.set("If-None-Exist", ifNoneExist);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * The Patient P in {@code body} (a resource or a search bundle) carries
     * A's MRN, and nothing of B's registration: neither its MRN nor its
     * hospital id (the MRN identifier system names the hospital).
     */
    private void assertOnlyTheMrnAtA(String body, String what) {
        JsonNode node = objectMapper.readTree(body);
        JsonNode patient = node.has("entry") ? node.get("entry").get(0).get("resource") : node;
        List<String> mrns = new ArrayList<>();
        patient.get("identifier").forEach(identifier -> {
            if (identifier.path("system").asText().endsWith(":mrn")) {
                mrns.add(identifier.path("system").asText() + "|" + identifier.path("value").asText());
            }
        });
        assertThat(mrns).as(what).containsExactly("urn:hms:hospital:" + hospitalA.getId() + ":mrn|" + mrnOfPAtA);
        assertThat(body).as(what).doesNotContain(mrnOfPAtB).doesNotContain(hospitalB.getId().toString());
    }

    private ResponseEntity<String> get(String path, String bearer, String hospitalHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, FHIR_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (hospitalHeader != null) {
            headers.set(HOSPITAL_HEADER, hospitalHeader);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private static List<String> entryIds(JsonNode bundle) {
        List<String> ids = new ArrayList<>();
        JsonNode entries = bundle.get("entry");
        if (entries != null) {
            entries.forEach(e -> ids.add(e.get("resource").get("id").asText()));
        }
        return ids;
    }

    /** A search bundle minus what differs per response by construction: its own id and timestamp. */
    private String normalisedBundle(ResponseEntity<String> response, String namedPatient) {
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(response.getBody());
        bundle.remove("id");
        bundle.remove("meta");
        return bundle.toString().replace(namedPatient, "{patient}");
    }

    private String legacyToken(User user, String... roles) {
        // A login touches the idle tracker; a token minted here must too, or
        // the idle gate answers 401 before the request reaches FHIR.
        idleSessionTracker.touch(user.getId());
        return jwtTokenProvider.generateAccessToken(
            new TokenUserDescriptor(user.getId(), user.getUsername(), List.of(roles)));
    }

    // --------------------------------------------------------------- fixtures

    private void seedRows(Rows rows, Hospital hospital, Staff author, UserRoleHospitalAssignment assignment) {
        Encounter encounter = encounterRepository.save(Encounter.builder()
            .patient(patientP)
            .staff(author)
            .hospital(hospital)
            .assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now())
            .code("ENC-FB-" + nextId())
            .build());
        rows.encounter = encounter.getId();
        rows.condition = problemRepository.save(PatientProblem.builder()
            .patient(patientP)
            .hospital(hospital)
            .recordedBy(author)
            .problemDisplay("Paludisme")
            .problemCode("B54")
            .status(ProblemStatus.ACTIVE)
            .severity(ProblemSeverity.MODERATE)
            .onsetDate(LocalDate.now().minusDays(3))
            .build()).getId();
        rows.immunization = immunizationRepository.save(PatientImmunization.builder()
            .patient(patientP)
            .hospital(hospital)
            .vaccineDisplay("BCG")
            .administrationDate(LocalDate.now().minusDays(10))
            .status("COMPLETED")
            .build()).getId();
        rows.prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patientP)
            .staff(author)
            .encounter(encounter)
            .hospital(hospital)
            .assignment(assignment)
            .medicationName("Amoxicilline 500 mg")
            .quantity(BigDecimal.TEN)
            .status(PrescriptionStatus.SIGNED)
            .build()).getId();
    }

    private Hospital saveHospital(String name) {
        String n = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + n)
            .code("HFB" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226556" + n)
            .email("fb" + n + "@hospital.test")
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
            .username(prefix + "-fb-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8))
            .passwordHash("hashed-password")
            .email(prefix + suffix + UUID.randomUUID().toString().substring(0, 8) + "@boundary.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22676" + suffix)
            .isActive(true)
            .build());
        users.add(user.getId());
        return user;
    }

    private UserRoleHospitalAssignment saveAssignment(User user, Role role, Hospital at) {
        UserRoleHospitalAssignment saved = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-FB-" + nextId())
            .description(role.getName() + " assignment")
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        assignments.add(saved.getId());
        return saved;
    }

    private Staff saveStaff(User user, Hospital hospital, UserRoleHospitalAssignment assignment) {
        Staff saved = staffRepository.save(Staff.builder()
            .user(user)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-FB-" + nextId())
            .name("Dr. " + user.getFirstName())
            .active(true)
            .build());
        staff.add(saved.getId());
        return saved;
    }

    private Patient savePatient(Hospital firstSeenAt) {
        String suffix = nextId();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Aminata")
            .lastName("Boundary" + suffix)
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22675" + suffix)
            .email("patient" + suffix + UUID.randomUUID().toString().substring(0, 8) + "@boundary.test")
            .organizationId(organization.getId())
            .hospitalId(firstSeenAt.getId())
            .user(saveUser("patient"))
            .build());
        patients.add(saved.getId());
        return saved;
    }

    /** Registers the patient and answers the MRN it was given. */
    private String register(Patient patient, Hospital hospital) {
        String mrn = "MRN-FB-" + nextId();
        registrations.add(registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn(mrn)
            .registrationDate(LocalDate.now())
            .active(true)
            .build()).getId());
        return mrn;
    }

    private String nextId() {
        return RUN + String.format("%05d", SEQUENCE.incrementAndGet());
    }

    /**
     * Stands in for {@code OidcResourceServerConfig}, which discovers its
     * decoder over HTTP: a decoder for the fixture's key and the issuer-aware
     * resolver, so Keycloak-shaped tokens take the real OIDC path while the
     * legacy HMS tokens still reach {@code JwtAuthenticationFilter}.
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

        /**
         * Production registers this filter only when
         * {@code app.auth.oidc.issuer-uri} is set, which would also run the
         * HTTP discovery above; declared here instead, and wired into the
         * chain by the same {@code SecurityConfig} code as in production.
         */
        @Bean
        KeycloakHospitalContextFilter keycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                                                    IdleSessionGate idleSessionGate,
                                                                    UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);
        }
    }
}
