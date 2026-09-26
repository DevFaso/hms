package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code /users} through the REAL security filter chain with real bearer
 * tokens: the account-takeover hole (any authenticated principal could
 * {@code PUT /users/<id> {"password": ...}}) is closed, and every legitimate
 * caller the portal has still works.
 *
 * <p>Callers mapped before the rules were chosen:
 * <ul>
 *   <li>{@code GET /users}: chat's new-conversation picker (staff), staff-list's
 *       account picker; {@code GET /users/search}: user-list and the
 *       super-admin emergency MFA-reset picker;</li>
 *   <li>{@code GET/PUT /users/{own id}}: the profile page, for every signed-in
 *       user including patients (names, email, phone; the username is sent
 *       back unchanged);</li>
 *   <li>{@code GET/PUT/DELETE /users/{id}}, {@code PATCH .../restore}: the
 *       user-list admin page (super-admin);</li>
 *   <li>{@code DELETE /users/{id}}: patient-form's compensation when the
 *       patient row fails after admin-register created the account.</li>
 * </ul>
 */
// OidcResourceServerIntegrationTest's configuration, like FhirRoleGateIT, so
// the classes share one cached context.
@AutoConfigureMockMvc
@Import(OidcResourceServerIntegrationTest.OidcTestConfig.class)
class UserEndpointAuthorizationIT extends BaseIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String ORIGINAL_HASH_PASSWORD = "Original-Pass-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();
    private final List<UUID> createdPatients = new ArrayList<>();

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User superAdmin;
    private User adminA;
    private User receptionistA;
    private User nurseA;
    private User doctorB;
    private User patientA;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Users Gate Network " + next())
            .code("UGN" + next())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospitalA = saveHospital("Users Gate A");
        hospitalB = saveHospital("Users Gate B");

        superAdmin = account("sa", "ROLE_SUPER_ADMIN", null);
        adminA = account("hadmA", "ROLE_HOSPITAL_ADMIN", hospitalA);
        receptionistA = account("recA", "ROLE_RECEPTIONIST", hospitalA);
        nurseA = account("nurA", "ROLE_NURSE", hospitalA);
        doctorB = account("docB", "ROLE_DOCTOR", hospitalB);
        patientA = account("patA", "ROLE_PATIENT", hospitalA);
    }

    @AfterEach
    void tearDown() {
        auditEventLogRepository.deleteAllInBatch();
        for (UUID patientId : createdPatients) {
            registrationRepository.deleteAll(registrationRepository.findByPatientId(patientId));
        }
        patientRepository.deleteAllById(createdPatients);
        // Includes accounts admin-register made during a test.
        for (UUID userId : createdUsers) {
            staffRepository.deleteAll(staffRepository.findByUserId(userId));
            assignmentRepository.deleteAll(assignmentRepository.findByUserId(userId));
        }
        userRepository.deleteAllById(createdUsers);
        roleRepository.deleteAllById(createdRoles);
        hospitalRepository.deleteAll(List.of(hospitalA, hospitalB));
        organizationRepository.delete(organization);
    }

    // ------------------------------------------------------------ the hole

    @Test
    @DisplayName("a patient token cannot take over the super-admin: password, status, rename and delete all refused, hash unchanged")
    void patientCannotTakeOverSuperAdmin() throws Exception {
        String hashBefore = hashOf(superAdmin);
        for (String patient : new String[] {hms(patientA, "ROLE_PATIENT"), keycloakPatient()}) {
            assertThat(status(put("/users/" + superAdmin.getId()), patient,
                Map.of("password", "Attacker-Chosen-1"))).isEqualTo(404);
            assertThat(status(put("/users/" + superAdmin.getId()), patient,
                Map.of("active", false))).isEqualTo(404);
            assertThat(status(put("/users/" + superAdmin.getId()), patient,
                Map.of("username", "pwned", "email", "attacker@evil.test"))).isEqualTo(404);
            assertThat(status(delete("/users/" + superAdmin.getId()), patient, null)).isEqualTo(403);
            assertThat(status(get("/users/" + superAdmin.getId()), patient, null)).isEqualTo(404);
        }

        User after = userRepository.findById(superAdmin.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(passwordEncoder.matches("Attacker-Chosen-1", after.getPasswordHash())).isFalse();
        assertThat(after.isActive()).isTrue();
        assertThat(after.isDeleted()).isFalse();
        assertThat(after.getUsername()).isEqualTo(superAdmin.getUsername());
    }

    @Test
    @DisplayName("a refused id answers exactly like a missing one")
    void refusalIsNotAnExistenceOracle() throws Exception {
        String patient = hms(patientA, "ROLE_PATIENT");
        MvcResult refused = perform(put("/users/" + nurseA.getId()), patient, Map.of("firstName", "X"));
        UUID missing = UUID.randomUUID();
        MvcResult absent = perform(put("/users/" + missing), patient, Map.of("firstName", "X"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(absent.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(refused).replace(nurseA.getId().toString(), "<id>"))
            .isEqualTo(message(absent).replace(missing.toString(), "<id>"));
    }

    @Test
    @DisplayName("a patient cannot list or search the directory, on either auth path")
    void patientCannotEnumerateAccounts() throws Exception {
        for (String patient : new String[] {hms(patientA, "ROLE_PATIENT"), keycloakPatient()}) {
            assertThat(status(get("/users"), patient, null)).isEqualTo(403);
            assertThat(status(get("/users/search").param("name", "a"), patient, null)).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("staff who are not administrators cannot edit or read another account")
    void staffCannotEditOthers() throws Exception {
        String doctor = hms(doctorB, "ROLE_DOCTOR");
        assertThat(status(put("/users/" + nurseA.getId()), doctor, Map.of("password", "Attacker-Chosen-1")))
            .isEqualTo(404);
        assertThat(status(get("/users/" + nurseA.getId()), doctor, null)).isEqualTo(404);
        assertThat(status(delete("/users/" + nurseA.getId()), doctor, null)).isEqualTo(404);
    }

    @Test
    @DisplayName("a hospital admin cannot edit a user at another hospital, nor any super-admin, nor restore one")
    void hospitalAdminStaysInTheirHospital() throws Exception {
        String admin = hms(adminA, "ROLE_HOSPITAL_ADMIN");
        String doctorHash = hashOf(doctorB);

        assertThat(status(put("/users/" + doctorB.getId()), admin, Map.of("password", "Attacker-Chosen-1")))
            .isEqualTo(404);
        assertThat(status(put("/users/" + superAdmin.getId()), admin, Map.of("password", "Attacker-Chosen-1")))
            .isEqualTo(404);
        assertThat(status(delete("/users/" + doctorB.getId()), admin, null)).isEqualTo(404);
        assertThat(status(patch("/users/" + doctorB.getId() + "/restore"), admin, null)).isEqualTo(404);

        assertThat(hashOf(doctorB)).isEqualTo(doctorHash);
        assertThat(userRepository.findById(doctorB.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("a hospital admin cannot rename their nurse to a case variant of the super-admin's username")
    void caseVariantRenameCannotLockOutTheSuperAdmin() throws Exception {
        String admin = hms(adminA, "ROLE_HOSPITAL_ADMIN");
        String variant = superAdmin.getUsername().toUpperCase(java.util.Locale.ROOT);

        MvcResult refused = perform(put("/users/" + nurseA.getId()), admin, Map.of("username", variant));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
            .doesNotContainIgnoringCase(superAdmin.getUsername());
        assertThat(userRepository.findById(nurseA.getId()).orElseThrow().getUsername())
            .isEqualTo(nurseA.getUsername());
        // The super-admin's login lookup still resolves to exactly one row.
        assertThat(userRepository.findByUsernameIgnoreCase(variant)).map(User::getId).contains(superAdmin.getId());

        String email = superAdmin.getEmail().toUpperCase(java.util.Locale.ROOT);
        assertThat(status(put("/users/" + nurseA.getId()), admin, Map.of("email", email))).isEqualTo(400);
    }

    @Test
    @DisplayName("a hospital admin cannot reset a patient who is assigned here but registered at another hospital")
    void patientRegisteredElsewhereIsNotThisAdminsToReset() throws Exception {
        String admin = hms(adminA, "ROLE_HOSPITAL_ADMIN");
        Patient record = patientRow(patientA);
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(record)
            .hospital(hospitalB)
            .mrn("MRN-B-" + next())
            .registrationDate(LocalDate.now())
            .active(false)
            .build());
        String hashBefore = hashOf(patientA);

        assertThat(status(put("/users/" + patientA.getId()), admin, Map.of("password", "Admin-Reset-Pass-1")))
            .isEqualTo(404);
        assertThat(hashOf(patientA)).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("a refused cross-account write answers 404 and leaves one FAILURE row: actor and target ids, no values")
    void refusedWriteIsAudited() throws Exception {
        auditEventLogRepository.deleteAllInBatch();
        String doctor = hms(doctorB, "ROLE_DOCTOR");
        String hashBefore = hashOf(nurseA);

        assertThat(status(put("/users/" + nurseA.getId()), doctor,
            Map.of("password", "Attacker-Chosen-1", "email", "attacker@evil.test"))).isEqualTo(404);

        List<AuditEventLog> failures = auditEventLogRepository.findAll().stream()
            .filter(row -> nurseA.getId().toString().equals(row.getResourceId()))
            .filter(row -> row.getStatus() == AuditStatus.FAILURE)
            .toList();
        assertThat(failures).hasSize(1);
        AuditEventLog row = failures.get(0);
        assertThat(row.getUser()).isNotNull();
        assertThat(row.getUser().getId()).isEqualTo(doctorB.getId());
        assertThat(String.valueOf(row.getDetails()) + row.getEventDescription() + row.getResourceName())
            .doesNotContain("Attacker").doesNotContain("evil").doesNotContain(nurseA.getUsername());
        assertThat(hashOf(nurseA)).isEqualTo(hashBefore);
    }

    // ------------------------------------------------ the legitimate callers

    @Test
    @DisplayName("a hospital admin edits an account at their own hospital")
    void hospitalAdminEditsOwnStaff() throws Exception {
        String admin = hms(adminA, "ROLE_HOSPITAL_ADMIN");

        assertThat(status(put("/users/" + nurseA.getId()), admin,
            Map.of("password", "Admin-Reset-Pass-1", "firstName", "Renamed"))).isEqualTo(200);

        User after = userRepository.findById(nurseA.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Admin-Reset-Pass-1", after.getPasswordHash())).isTrue();
        assertThat(after.getFirstName()).isEqualTo("Renamed");
        assertThat(status(get("/users/" + nurseA.getId()), admin, null)).isEqualTo(200);
    }

    @Test
    @DisplayName("the super-admin's user list: list, search, read, edit, delete and restore anyone")
    void superAdminUserList() throws Exception {
        String sa = hms(superAdmin, "ROLE_SUPER_ADMIN");

        assertThat(status(get("/users").param("onlyDeleted", "true"), sa, null)).isEqualTo(200);
        assertThat(status(get("/users/search").param("name", "doc"), sa, null)).isEqualTo(200);
        assertThat(status(get("/users/" + doctorB.getId()), sa, null)).isEqualTo(200);
        assertThat(status(put("/users/" + doctorB.getId()), sa, Map.of("password", "Sa-Reset-Pass-1")))
            .isEqualTo(200);
        assertThat(passwordEncoder.matches("Sa-Reset-Pass-1", hashOf(doctorB))).isTrue();
        assertThat(status(delete("/users/" + doctorB.getId()), sa, null)).isEqualTo(200);
        assertThat(status(patch("/users/" + doctorB.getId() + "/restore"), sa, null)).isEqualTo(204);
        assertThat(userRepository.findById(doctorB.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("chat and staff-list: any staff member lists the directory")
    void staffListTheDirectory() throws Exception {
        assertThat(status(get("/users").param("size", "100"), hms(doctorB, "ROLE_DOCTOR"), null)).isEqualTo(200);
        assertThat(status(get("/users").param("size", "500"), hms(receptionistA, "ROLE_RECEPTIONIST"), null))
            .isEqualTo(200);
    }

    @Test
    @DisplayName("the profile page: a patient reads and edits their own contact fields, but not their password or status")
    void profileSelfEdit() throws Exception {
        String patient = hms(patientA, "ROLE_PATIENT");
        String hashBefore = hashOf(patientA);

        assertThat(status(get("/users/" + patientA.getId()), patient, null)).isEqualTo(200);
        assertThat(status(put("/users/" + patientA.getId()), patient, Map.of(
            "firstName", "Awa", "lastName", "Traore", "email", "awa" + next() + "@self.test",
            "phoneNumber", "+22671" + next(), "username", patientA.getUsername()))).isEqualTo(200);
        assertThat(userRepository.findById(patientA.getId()).orElseThrow().getFirstName()).isEqualTo("Awa");

        assertThat(status(put("/users/" + patientA.getId()), patient, Map.of("password", "Self-Set-Pass-1")))
            .isEqualTo(400);
        assertThat(status(put("/users/" + patientA.getId()), patient, Map.of("active", false))).isEqualTo(400);
        assertThat(hashOf(patientA)).isEqualTo(hashBefore);
        assertThat(userRepository.findById(patientA.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("patient-form's compensation: the receptionist discards the account its failed registration just made, and nothing else")
    void receptionistCompensationDelete() throws Exception {
        String receptionist = hms(receptionistA, "ROLE_RECEPTIONIST");

        // Step 1 of patient-form, through the real chain: admin-register a PATIENT.
        String suffix = next();
        MvcResult registered = perform(post("/users/admin-register"), receptionist, Map.of(
            "username", "orphan" + suffix,
            "firstName", "Orphan",
            "lastName", "Account",
            "phoneNumber", "+22672" + suffix,
            "roleNames", List.of("PATIENT"),
            "hospitalId", hospitalA.getId().toString(),
            "forcePasswordChange", true));
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        UUID orphanId = UUID.fromString(objectMapper.readTree(
            registered.getResponse().getContentAsString()).get("id").asText());
        createdUsers.add(orphanId);

        // Step 2 failed (no patient row); the compensation deletes the account.
        assertThat(status(delete("/users/" + orphanId), receptionist, null)).isEqualTo(200);
        assertThat(userRepository.findById(orphanId).orElseThrow().isDeleted()).isTrue();

        // The same call against anyone else is refused as missing.
        Patient realPatient = patientRow(patientA);
        assertThat(status(delete("/users/" + patientA.getId()), receptionist, null)).isEqualTo(404);
        assertThat(status(delete("/users/" + nurseA.getId()), receptionist, null)).isEqualTo(404);
        assertThat(status(delete("/users/" + superAdmin.getId()), receptionist, null)).isEqualTo(404);
        assertThat(userRepository.findById(patientA.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(realPatient.getId()).isNotNull();
    }

    // ------------------------------------------------ who may grant what

    @Test
    @DisplayName("a nurse requesting SUPER_ADMIN gets 403, no account is created, and one FAILURE row names the roles only")
    void nurseCannotMintASuperAdmin() throws Exception {
        auditEventLogRepository.deleteAllInBatch();
        String nurse = hms(nurseA, "ROLE_NURSE");
        String suffix = next();
        String username = "minted" + suffix;

        MvcResult refused = perform(post("/users/admin-register"), nurse, Map.of(
            "username", username,
            "email", username + "@evil.test",
            "password", "Chosen-Pass-1",
            "firstName", "Mint",
            "lastName", "Admin",
            "phoneNumber", "+22676" + suffix,
            "roleNames", List.of("SUPER_ADMIN")));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(userRepository.findByUsernameIgnoreCase(username)).isEmpty();
        List<AuditEventLog> failures = auditEventLogRepository.findAll().stream()
            .filter(row -> row.getStatus() == AuditStatus.FAILURE)
            .toList();
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).getUser().getId()).isEqualTo(nurseA.getId());
        assertThat(String.valueOf(failures.get(0).getDetails())).contains("SUPER_ADMIN")
            .doesNotContain(username).doesNotContain("Chosen").doesNotContain("evil");

        // Nor any other staff role, even at the nurse's own hospital.
        Map<String, Object> doctor = new java.util.HashMap<>(signup("docmint", "DOCTOR", hospitalA));
        doctor.put("licenseNumber", "LIC-" + next());
        assertThat(status(post("/users/admin-register"), nurse, doctor)).isEqualTo(403);
    }

    @Test
    @DisplayName("a hospital admin cannot grant HOSPITAL_ADMIN, nor any role at another hospital")
    void hospitalAdminCannotGrantAdminOrElsewhere() throws Exception {
        String admin = hms(adminA, "ROLE_HOSPITAL_ADMIN");

        assertThat(status(post("/users/admin-register"), admin, signup("peer", "HOSPITAL_ADMIN", hospitalA)))
            .isEqualTo(403);
        assertThat(status(post("/users/admin-register"), admin, signup("sa", "SUPER_ADMIN", hospitalA)))
            .isEqualTo(403);
        assertThat(status(post("/users/admin-register"), admin, signup("recB", "RECEPTIONIST", hospitalB)))
            .isEqualTo(403);

        // A PATIENT registration lands at the caller's own active hospital
        // whatever hospitalId the body names (resolveHospitalForPatient reads
        // the context first), so this one is allowed, and is created at A.
        MvcResult patient = perform(post("/users/admin-register"), admin, signup("patB", "PATIENT", hospitalB));
        assertThat(patient.getResponse().getStatus()).isEqualTo(201);
        UUID patientId = idOf(patient);
        createdUsers.add(patientId);
        assertThat(assignmentRepository.findByUserId(patientId))
            .allSatisfy(a -> assertThat(a.getHospital().getId()).isEqualTo(hospitalA.getId()));
    }

    @Test
    @DisplayName("staff creation still works: a hospital admin at home, the super-admin anywhere")
    void staffCreationStillWorks() throws Exception {
        Map<String, Object> receptionist = new java.util.HashMap<>(signup("recnew", "RECEPTIONIST", hospitalA));
        receptionist.put("jobTitle", "RECEPTIONIST");
        MvcResult byAdmin = perform(post("/users/admin-register"), hms(adminA, "ROLE_HOSPITAL_ADMIN"), receptionist);
        assertThat(byAdmin.getResponse().getStatus()).isEqualTo(201);
        createdUsers.add(idOf(byAdmin));

        Map<String, Object> doctor = new java.util.HashMap<>(signup("docnew", "DOCTOR", hospitalB));
        doctor.put("licenseNumber", "LIC-" + next());
        doctor.put("jobTitle", "DOCTOR");
        MvcResult bySuperAdmin = perform(post("/users/admin-register"), hms(superAdmin, "ROLE_SUPER_ADMIN"), doctor);
        assertThat(bySuperAdmin.getResponse().getStatus()).isEqualTo(201);
        createdUsers.add(idOf(bySuperAdmin));
    }

    @Test
    @DisplayName("a hospital admin cannot take over a peer admin at the same hospital")
    void hospitalAdminCannotTakeOverPeer() throws Exception {
        User peer = account("hadmA2", "ROLE_HOSPITAL_ADMIN", hospitalA);
        String hashBefore = hashOf(peer);

        assertThat(status(put("/users/" + peer.getId()), hms(adminA, "ROLE_HOSPITAL_ADMIN"),
            Map.of("password", "Takeover-Pass-1"))).isEqualTo(404);
        assertThat(hashOf(peer)).isEqualTo(hashBefore);
        assertThat(status(put("/users/" + peer.getId()), hms(superAdmin, "ROLE_SUPER_ADMIN"),
            Map.of("firstName", "Renamed"))).isEqualTo(200);
    }

    @Test
    @DisplayName("a surgeon's compensation works: register the patient, discard the orphan")
    void surgeonCompensation() throws Exception {
        User surgeon = account("surA", "ROLE_SURGEON", hospitalA);
        String token = hms(surgeon, "ROLE_SURGEON");

        MvcResult registered = perform(post("/users/admin-register"), token, signup("orphan", "PATIENT", hospitalA));
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        UUID orphanId = idOf(registered);
        createdUsers.add(orphanId);

        assertThat(status(delete("/users/" + orphanId), token, null)).isEqualTo(200);
        assertThat(userRepository.findById(orphanId).orElseThrow().isDeleted()).isTrue();
    }

    // -------------------------------------------------------------- helpers

    private String next() {
        return String.format("%05d", SEQUENCE.incrementAndGet());
    }

    private Hospital saveHospital(String name) {
        String id = next();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + id)
            .code("UG" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226556" + id)
            .email("users-gate" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Role ensureRole(String code) {
        return roleRepository.findByCode(code).orElseGet(() -> {
            Role saved = roleRepository.save(Role.builder()
                .name(code.substring("ROLE_".length()))
                .code(code)
                .description(code)
                .build());
            createdRoles.add(saved.getId());
            return saved;
        });
    }

    private User account(String prefix, String roleCode, Hospital hospital) {
        String suffix = next();
        User user = userRepository.save(User.builder()
            .username(prefix + suffix)
            .passwordHash(passwordEncoder.encode(ORIGINAL_HASH_PASSWORD))
            .email(prefix + suffix + "@users-gate.test")
            .firstName(prefix)
            .lastName("Gate" + suffix)
            .phoneNumber("+22673" + suffix)
            .isActive(true)
            .build());
        createdUsers.add(user.getId());
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("UG-" + next())
            .description(roleCode)
            .user(user)
            .hospital(hospital)
            .role(ensureRole(roleCode))
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return user;
    }

    private Patient patientRow(User user) {
        String suffix = next();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Aminata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22674" + suffix)
            .email("aminata" + suffix + "@patient.test")
            .emergencyContactName("Issa Diallo")
            .emergencyContactPhone("+22675" + suffix)
            .organizationId(organization.getId())
            .hospitalId(hospitalA.getId())
            .user(user)
            .build());
        createdPatients.add(saved.getId());
        return saved;
    }

    private Map<String, Object> signup(String prefix, String role, Hospital hospital) {
        String suffix = next();
        return Map.of(
            "username", prefix + suffix,
            "email", prefix + suffix + "@signup.test",
            "firstName", prefix,
            "lastName", "Signup" + suffix,
            "phoneNumber", "+22677" + suffix,
            "roleNames", List.of(role),
            "hospitalId", hospital.getId().toString());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String hashOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getPasswordHash();
    }

    /** An HMS-minted token, read back by JwtAuthenticationFilter as after a password login. */
    private String hms(User user, String role) {
        idleSessionTracker.touch(user.getId());
        return jwtTokenProvider.generateAccessToken(
            new TokenUserDescriptor(user.getId(), user.getUsername(), List.of(role)));
    }

    /** A Keycloak patient token, with the default realm roles Keycloak adds to everyone. */
    private String keycloakPatient() {
        return keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(OidcResourceServerIntegrationTest.TEST_ISSUER, OidcResourceServerIntegrationTest.TEST_AUDIENCE)
            .withRealmRoles(List.of("PATIENT", "offline_access", "uma_authorization", "default-roles-hms")));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        // With a valid CSRF token: /users is not CSRF-exempt, and CSRF is no
        // defence against a caller who holds a bearer token anyway, since
        // GET /auth/csrf-token hands one to anyone. The authorization decision
        // is what is under test.
        request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        return perform(request, bearer, body).getResponse().getStatus();
    }

    private String message(MvcResult result) throws Exception {
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.has("message") ? node.get("message").asText() : node.toString();
    }
}
