package com.example.hms.security.tenant;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.ActorType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.UserRoleId;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A patient registered at TWO hospitals, through the real security chain on
 * both auth paths, never sending {@code X-Hospital-Id} (the patient apps never
 * do): design Q1, a patient is bounded by ownership, not by a pinned hospital.
 * No request may answer AMBIGUOUS / 400, every list returns the rows of BOTH
 * hospitals (develop pinned a two-hospital patient to the first hospital and
 * silently hid the other's rows), and every write lands at the hospital of the
 * record it acts on, or the hospital the body names, checked against the
 * patient's registrations.
 */
@SpringBootTest(classes = HmsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({TestPostgresConfig.class, TenantResolutionIT.OidcTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PatientTwoHospitalsIT {

    private static final String CSRF = "patient-two-hospitals-it-csrf";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "password";
    private static final String KEYCLOAK = "keycloak";

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private BillingInvoiceRepository invoiceRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabResultRepository labResultRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private final List<UUID> organizations = new ArrayList<>();
    private final List<UUID> hospitals = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    /** One hospital's side of the fixture: its doctor, department and the patient's rows there. */
    private record Site(Hospital hospital, User doctor, Staff staff, Department department,
                        Prescription prescription, BillingInvoice invoice, LabResult labResult, Encounter encounter) { }

    private Site siteA;
    private Site siteB;
    private User patientUser;
    private Patient patient;

    @BeforeEach
    void setUp() {
        Role patientRole = ensureRole("ROLE_PATIENT", "Patient");
        Role doctorRole = ensureRole("ROLE_DOCTOR", "Doctor");

        Hospital a = saveHospital("Two A");
        Hospital b = saveHospital("Two B");

        patientUser = saveUser("pt2");
        grant(patientUser, patientRole);
        assign(patientUser, patientRole, a);
        assign(patientUser, patientRole, b);
        String suffix = nextId();
        patient = patientRepository.save(Patient.builder()
            .firstName("Mariam")
            .lastName("Two" + suffix)
            .dateOfBirth(LocalDate.of(1988, 5, 2))
            .gender("F")
            .address("Patient address")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22670" + suffix)
            .email("pt2" + suffix + "@patient.test")
            .hospitalId(a.getId())
            .organizationId(a.getOrganization().getId())
            .user(patientUser)
            .build());
        register(a);
        register(b);

        siteA = site(a, doctorRole);
        siteB = site(b, doctorRole);
    }

    @AfterEach
    void tearDown() {
        // Everything this class made, and everything the requests made on top
        // of it (appointments, refills, chat rows, opt-outs, audit rows), found
        // through the database's own foreign keys, so a sibling IT's table-wide
        // delete never trips on a row left here.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Set<String> seen = new HashSet<>();
            collectAndDelete("clinical", "patients", "ID", List.of(patient.getId()), seen, 0);
            collectAndDelete("security", "users", "ID", users, seen, 0);
            collectAndDelete("hospital", "hospitals", "ID", hospitals, seen, 0);
            collectAndDelete("hospital", "organizations", "ID", organizations, seen, 0);
        });
    }

    // ── reads: both hospitals, no header, never 400 ─────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("lab results, prescriptions, invoices and visits list BOTH hospitals' rows (develop showed only the first)")
    void readsCoverBothHospitals(String path) throws Exception {
        String token = token(path);

        assertThat(ids(get("/me/patient/lab-results", token), "/data"))
            .contains(siteA.labResult().getId().toString(), siteB.labResult().getId().toString());
        assertThat(ids(get("/me/patient/prescriptions", token), "/data"))
            .contains(siteA.prescription().getId().toString(), siteB.prescription().getId().toString());
        assertThat(ids(get("/me/patient/billing/invoices", token), "/data/content"))
            .contains(siteA.invoice().getId().toString(), siteB.invoice().getId().toString());
        assertThat(ids(get("/me/patient/encounters", token), "/data"))
            .contains(siteA.encounter().getId().toString(), siteB.encounter().getId().toString());
        for (String page : List.of("/me/patient/after-visit-summaries", "/me/patient/consultations",
                "/me/patient/treatment-plans", "/me/patient/referrals", "/me/patient/medications",
                "/me/patient/health-summary", "/me/patient/refills")) {
            ResponseEntity<String> response = get(page, token);
            assertThat(response.getStatusCode().value()).as(page + ": " + response.getBody()).isEqualTo(200);
        }
    }

    // ── appointments: list, book at each hospital, cancel one ───────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("book at each hospital (the body's hospital, checked against registrations), list both, cancel one")
    void appointments(String path) throws Exception {
        String token = token(path);
        UUID atA = book(token, siteA, 9);
        UUID atB = book(token, siteB, 10);

        assertThat(appointmentRepository.findById(atA).orElseThrow().getHospital().getId())
            .isEqualTo(siteA.hospital().getId());
        assertThat(appointmentRepository.findById(atB).orElseThrow().getHospital().getId())
            .isEqualTo(siteB.hospital().getId());
        assertThat(ids(get("/me/patient/appointments", token), "/data"))
            .contains(atA.toString(), atB.toString());

        ResponseEntity<String> cancel = send(HttpMethod.PUT, "/me/patient/appointments/cancel",
            "{\"appointmentId\":\"" + atB + "\",\"reason\":\"travel\"}", token);
        assertThat(cancel.getStatusCode().value()).as(cancel.getBody()).isEqualTo(200);
        assertThat(appointmentRepository.findById(atB).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");

        Hospital unregistered = saveHospital("Two C");
        ResponseEntity<String> elsewhere = send(HttpMethod.POST, "/me/patient/appointments",
            bookingBody(unregistered.getId(), siteA.department().getId(), siteA.staff().getId(), 11), token);
        assertThat(elsewhere.getStatusCode().value()).as("a hospital the patient is not registered at")
            .isEqualTo(400);
    }

    // ── writes on a record carry the record's hospital ──────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("a refill on B's prescription and a payment on B's invoice act on B's records")
    void refillAndPayment(String path) throws Exception {
        String token = token(path);

        ResponseEntity<String> refill = send(HttpMethod.POST, "/me/patient/refills",
            "{\"prescriptionId\":\"" + siteB.prescription().getId() + "\",\"notes\":\"running low\"}", token);
        assertThat(refill.getStatusCode().is2xxSuccessful()).as(refill.getBody()).isTrue();

        ResponseEntity<String> pay = send(HttpMethod.POST,
            "/me/patient/billing/invoices/" + siteB.invoice().getId() + "/pay",
            "{\"amount\":25.00,\"paymentMethod\":\"MOBILE_MONEY\"}", token);
        assertThat(pay.getStatusCode().value()).as(pay.getBody()).isEqualTo(200);
        BillingInvoice paid = invoiceRepository.findById(siteB.invoice().getId()).orElseThrow();
        assertThat(paid.getAmountPaid()).isEqualByComparingTo("25.00");
        assertThat(paid.getHospital().getId()).isEqualTo(siteB.hospital().getId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("a profile edit (Patient's tenant listener runs) keeps the record's own hospital; nothing is pinned")
    void entityListenerStampsTheRecordsHospital(String path) throws Exception {
        String token = token(path);
        ResponseEntity<String> update = send(HttpMethod.PUT, "/me/patient/profile",
            "{\"city\":\"Koudougou\"}", token);
        assertThat(update.getStatusCode().value()).as(update.getBody()).isEqualTo(200);

        Patient after = patientRepository.findByIdUnscoped(patient.getId()).orElseThrow();
        assertThat(after.getCity()).isEqualTo("Koudougou");
        assertThat(after.getHospitalId()).as("the record's hospital, not B, not null").isEqualTo(siteA.hospital().getId());
        assertThat(after.getOrganizationId()).isEqualTo(siteA.hospital().getOrganization().getId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("an ROI request is filed at the hospital the body names (checked), else the record's own; never guessed")
    void roiRequestHospital(String path) throws Exception {
        String token = token(path);
        String body = "{\"purpose\":\"insurance\",\"scopeDescription\":\"visits 2026\"%s}";

        ResponseEntity<String> atB = send(HttpMethod.POST, "/me/patient/roi-requests",
            body.formatted(",\"hospitalId\":\"" + siteB.hospital().getId() + "\""), token);
        assertThat(atB.getStatusCode().value()).as(atB.getBody()).isEqualTo(201);
        assertThat(roiHospital(atB)).isEqualTo(siteB.hospital().getId());

        ResponseEntity<String> unnamed = send(HttpMethod.POST, "/me/patient/roi-requests", body.formatted(""), token);
        assertThat(unnamed.getStatusCode().value()).as(unnamed.getBody()).isEqualTo(201);
        assertThat(roiHospital(unnamed)).as("the patient record's own hospital").isEqualTo(siteA.hospital().getId());

        Hospital unregistered = saveHospital("Two D");
        ResponseEntity<String> elsewhere = send(HttpMethod.POST, "/me/patient/roi-requests",
            body.formatted(",\"hospitalId\":\"" + unregistered.getId() + "\""), token);
        assertThat(elsewhere.getStatusCode().value()).as("not one of the patient's registrations").isEqualTo(400);
    }

    private UUID roiHospital(ResponseEntity<String> response) throws Exception {
        UUID id = UUID.fromString(objectMapper.readTree(response.getBody()).path("id").asText());
        return jdbc.queryForObject("SELECT hospital_id FROM clinical.roi_requests WHERE id = ?", UUID.class, id);
    }

    // ── chat ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("chat: send to a doctor at B, then read the thread")
    void chat(String path) throws Exception {
        String token = token(path);
        UUID doctorB = siteB.doctor().getId();

        ResponseEntity<String> sent = send(HttpMethod.POST, "/chat/send",
            "{\"recipientId\":\"" + doctorB + "\",\"content\":\"Question about my results\"}", token);
        assertThat(sent.getStatusCode().value()).as(sent.getBody()).isEqualTo(201);

        ResponseEntity<String> history = get("/chat/history/" + patientUser.getId() + "/" + doctorB, token);
        assertThat(history.getStatusCode().value()).as(history.getBody()).isEqualTo(200);
        assertThat(history.getBody()).contains("Question about my results");
        // (/chat/conversations reads no scope; its native query does not run on
        // the H2 test database, a pre-existing gap noted on the PR.)
    }

    // ── record-sharing opt-out ──────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {PASSWORD, KEYCLOAK})
    @DisplayName("record-sharing opt-out: read, set and revoke their own")
    void optOut(String path) throws Exception {
        String token = token(path);
        String base = "/patients/" + patient.getId() + "/record-sharing/opt-out";

        assertThat(get(base, token).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> set = send(HttpMethod.POST, base, "{\"reason\":\"privacy\"}", token);
        assertThat(set.getStatusCode().value()).as(set.getBody()).isEqualTo(200);
        ResponseEntity<String> revoke = send(HttpMethod.DELETE, base, null, token);
        assertThat(revoke.getStatusCode().value()).as(revoke.getBody()).isEqualTo(200);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String token(String path) {
        idleSessionTracker.touch(patientUser.getId());
        if (PASSWORD.equals(path)) {
            return jwtTokenProvider.generateAccessToken(
                new TokenUserDescriptor(patientUser.getId(), patientUser.getUsername(), List.of("ROLE_PATIENT")));
        }
        return keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(TenantResolutionIT.TEST_ISSUER, TenantResolutionIT.OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of("PATIENT"))
            .linkedTo(patientUser.getId(), patientUser.getUsername()));
    }

    private UUID book(String token, Site site, int hour) throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST, "/me/patient/appointments",
            bookingBody(site.hospital().getId(), site.department().getId(), site.staff().getId(), hour), token);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        return UUID.fromString(objectMapper.readTree(response.getBody()).at("/data/id").asText());
    }

    private static String bookingBody(UUID hospitalId, UUID departmentId, UUID staffId, int hour) {
        return "{\"hospitalId\":\"" + hospitalId + "\",\"departmentId\":\"" + departmentId
            + "\",\"staffId\":\"" + staffId + "\",\"date\":\"" + LocalDate.now().plusDays(7)
            + "\",\"startTime\":\"" + LocalTime.of(hour, 0) + "\",\"reason\":\"check-up\"}";
    }

    private List<String> ids(ResponseEntity<String> response, String pointer) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode array = objectMapper.readTree(response.getBody()).at(pointer);
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearer);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + CSRF);
        headers.set("X-XSRF-TOKEN", CSRF);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private Site site(Hospital hospital, Role doctorRole) {
        User doctor = saveUser("dr2");
        grant(doctor, doctorRole);
        UserRoleHospitalAssignment assignment = assign(doctor, doctorRole, hospital);
        Staff staff = staffRepository.save(Staff.builder()
            .user(doctor)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr. " + doctor.getFirstName())
            .active(true)
            .build());
        Department department = departmentRepository.save(Department.builder()
            .name("General " + nextId())
            .code("GEN" + nextId())
            .email("gen" + nextId() + "@dept.test")
            .phoneNumber("+226-111-0000")
            .active(true)
            .hospital(hospital)
            .assignment(assignment)
            .build());
        staff.setDepartment(department);
        staff = staffRepository.save(staff);

        Encounter encounter = encounterRepository.save(Encounter.builder()
            .patient(patient)
            .staff(staff)
            .hospital(hospital)
            .assignment(assignment)
            .encounterType(EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now().minusDays(3))
            .code("ENC-" + nextId())
            .build());
        Prescription prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patient)
            .staff(staff)
            .encounter(encounter)
            .hospital(hospital)
            .assignment(assignment)
            .medicationName("Amoxicilline 500 mg")
            .quantity(BigDecimal.TEN)
            .status(PrescriptionStatus.SIGNED)
            .build());
        BillingInvoice draft = BillingInvoice.builder()
            .patient(patient)
            .hospital(hospital)
            .encounter(encounter)
            .invoiceNumber("INV-" + nextId())
            .invoiceDate(LocalDate.now().minusDays(2))
            .dueDate(LocalDate.now().plusDays(28))
            .totalAmount(new BigDecimal("100.00"))
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.SENT)
            .build();
        draft.addItem(com.example.hms.model.InvoiceItem.builder()
            .itemDescription("Consultation")
            .quantity(1)
            .itemCategory(com.example.hms.enums.ItemCategory.GENERAL)
            .unitPrice(new BigDecimal("100.00"))
            .totalPrice(new BigDecimal("100.00"))
            .assignment(assignment)
            .build());
        BillingInvoice invoice = invoiceRepository.save(draft);
        // An issued invoice with a balance: the entity recomputes its total
        // from line items on every write, so set the issued state directly.
        jdbc.update("UPDATE billing.billing_invoices SET total_amount = 100.00, amount_paid = 0, status = 'SENT' "
            + "WHERE id = ?", invoice.getId());
        LabTestDefinition test = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("HGB" + nextId())
            .name("Hemoglobin " + nextId())
            .category("HEMATOLOGY")
            .unit("g/dL")
            .hospital(hospital)
            .assignment(assignment)
            .build());
        LabOrder order = labOrderRepository.save(LabOrder.builder()
            .patient(patient)
            .orderingStaff(staff)
            .labTestDefinition(test)
            .hospital(hospital)
            .assignment(assignment)
            .orderDatetime(LocalDateTime.now().minusDays(2))
            .clinicalIndication("Routine")
            .status(LabOrderStatus.RESULTED)
            .build());
        LabResult result = LabResult.builder()
            .labOrder(order)
            .actorType(ActorType.SYSTEM)
            .actorLabel("TWO-HOSPITALS-IT")
            .resultValue("13.1")
            .resultUnit("g/dL")
            .resultDate(LocalDateTime.now().minusDays(1))
            .testCode(test.getTestCode())
            .build();
        result.setReleased(true);
        result.setReleasedAt(LocalDateTime.now());
        result.setReleasedByDisplay("Lab");
        result = labResultRepository.save(result);
        return new Site(hospital, doctor, staff, department, prescription, invoice, result, encounter);
    }

    private void register(Hospital hospital) {
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN-2H-" + nextId())
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
    }

    private Role ensureRole(String code, String name) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code).code(code).description(name).build()));
    }

    private void grant(User user, Role role) {
        userRoleRepository.save(UserRole.builder()
            .id(new UserRoleId(user.getId(), role.getId()))
            .user(user)
            .role(role)
            .build());
    }

    private UserRoleHospitalAssignment assign(User user, Role role, Hospital at) {
        return assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-2H-" + nextId())
            .description(role.getCode() + " assignment")
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
    }

    private Hospital saveHospital(String name) {
        String n = nextId();
        Organization organization = organizationRepository.save(Organization.builder()
            .name(name + " network " + n)
            .code("ORG-2H-" + n)
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        organizations.add(organization.getId());
        Hospital hospital = hospitalRepository.save(Hospital.builder()
            .name(name + " " + n)
            .code("H2H" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226558" + n)
            .email("h2h" + n + "@hospital.test")
            .organization(organization)
            .build());
        hospitals.add(hospital.getId());
        return hospital;
    }

    private User saveUser(String prefix) {
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username(prefix + "-2h-" + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@two-hospitals.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22671" + suffix)
            .isActive(true)
            .build());
        users.add(user.getId());
        return user;
    }

    private static String nextId() {
        return RUN + String.format("%05d", SEQUENCE.incrementAndGet());
    }

    /**
     * Delete {@code ids} from {@code schema.table} and, first, every row that
     * points at them through a single-column foreign key, recursively,
     * children before parents. Read from the JDBC metadata, so it is the
     * database's own graph whatever the engine; names are matched ignoring
     * case and then used exactly as the database reports them.
     */
    private void collectAndDelete(String schema, String table, String column, List<UUID> ids, Set<String> seen,
                                  int depth) {
        String[] actual = actualName(schema, table);
        String idColumn = actualColumn(actual, column);
        if (ids.isEmpty() || depth > 12 || idColumn == null
            || !seen.add(actual[0] + "." + actual[1] + "." + idColumn + ":" + new LinkedHashSet<>(ids))) {
            return;
        }
        List<String[]> references = jdbc.execute((java.sql.Connection connection) -> {
            List<String[]> found = new ArrayList<>();
            try (java.sql.ResultSet keys = connection.getMetaData().getExportedKeys(null, actual[0], actual[1])) {
                while (keys.next()) {
                    if (keys.getShort("KEY_SEQ") == 1 && idColumn.equals(keys.getString("PKCOLUMN_NAME"))) {
                        found.add(new String[] {keys.getString("FKTABLE_SCHEM"), keys.getString("FKTABLE_NAME"),
                            keys.getString("FKCOLUMN_NAME")});
                    }
                }
            }
            return found;
        });
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Object[] args = ids.toArray();
        for (String[] reference : references) {
            String child = quote(reference[0]) + "." + quote(reference[1]);
            String childId = actualColumn(new String[] {reference[0], reference[1]}, "id");
            if (childId != null && !(reference[0] + "." + reference[1]).equals(actual[0] + "." + actual[1])) {
                List<UUID> childIds = jdbc.queryForList("SELECT " + quote(childId) + " FROM " + child + " WHERE "
                    + quote(reference[2]) + " IN (" + placeholders + ")", UUID.class, args);
                collectAndDelete(reference[0], reference[1], childId, childIds, seen, depth + 1);
            }
            jdbc.update("DELETE FROM " + child + " WHERE " + quote(reference[2]) + " IN (" + placeholders + ")", args);
        }
        jdbc.update("DELETE FROM " + quote(actual[0]) + "." + quote(actual[1]) + " WHERE " + quote(idColumn)
            + " IN (" + placeholders + ")", args);
    }

    private String[] actualName(String schema, String table) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.ResultSet tables = connection.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
                while (tables.next()) {
                    if (schema.equalsIgnoreCase(tables.getString("TABLE_SCHEM"))
                        && table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        return new String[] {tables.getString("TABLE_SCHEM"), tables.getString("TABLE_NAME")};
                    }
                }
            }
            throw new IllegalStateException("no table " + schema + "." + table);
        });
    }

    private String actualColumn(String[] table, String column) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.ResultSet columns = connection.getMetaData().getColumns(null, table[0], table[1], null)) {
                while (columns.next()) {
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return columns.getString("COLUMN_NAME");
                    }
                }
            }
            return null;
        });
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }
}
