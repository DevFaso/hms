package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA r81 D4260, end to end over the real controllers, services and database: a visit is created
 * only at the hospital the caller acts at, for a patient registered there and a clinician
 * assigned there.
 *
 * <p>Two hospitals of one organisation, each with its own doctor, department and patient. A
 * doctor of hospital B who names hospital A (by id or by code), a patient of A or a clinician of
 * A is refused (403 for a hospital not held, 404 for the patient and the clinician, as for an
 * unknown id), the answer carries nothing about the patient, and no visit exists afterwards. At
 * their own hospital both doctors still open a visit, and a doctor who holds both hospitals opens
 * one at the hospital the body names (not the one selected), recorded and audited there. A doctor
 * who is only a patient at the other hospital cannot write there either.
 */
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("r81 D4260: a visit is created only at the caller's own hospital")
class VisitWriteScopeIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

    private final AtomicInteger sequence = new AtomicInteger(
        java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000, 60_000));

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private Department departmentA;
    private Department departmentB;
    private Actor doctorA;
    private Actor doctorB;
    private Patient patientA;
    private Patient patientB;

    private record Actor(User user, Staff staff, UserRoleHospitalAssignment assignment, Hospital hospital) { }

    @BeforeEach
    void setUp() {
        wipe();
        organization = organizationRepository.save(Organization.builder()
            .name("Visit Scope Network").code("ORG-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN)
            .active(true).build());
        hospitalA = saveHospital("Visit Scope A");
        hospitalB = saveHospital("Visit Scope B");
        doctorA = actor("vsDoctorA", hospitalA);
        doctorB = actor("vsDoctorB", hospitalB);
        departmentA = saveDepartment(hospitalA, doctorA.assignment());
        departmentB = saveDepartment(hospitalB, doctorB.assignment());
        patientA = savePatient("Aminata", "Scopetest", hospitalA);
        patientB = savePatient("Binta", "Scopetest", hospitalB);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        wipe();
    }

    /** The visits (and their history) first: they pin the patient, the staff and the department. */
    private void wipe() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("DELETE FROM clinical.encounter_history");
        jdbc.update("DELETE FROM clinical.encounters");
        jdbc.update("DELETE FROM clinical.patient_record_sharing_optouts");
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("naming another hospital by id is refused (403), says nothing of the patient, creates nothing")
    void aVisitAtAHospitalTheCallerDoesNotHoldIsRefused() throws Exception {
        MvcResult refused = send(doctorB, hospitalB, visit(hospitalA.getId(), patientA, doctorA, departmentA));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertSaysNothingOf(refused, patientA);
        assertThat(encounterRepository.count()).isZero();
    }

    @Test
    @DisplayName("naming another hospital by its code is refused as well, and creates nothing")
    void aVisitAtAnotherHospitalNamedByCodeIsRefused() throws Exception {
        Map<String, Object> body = visit(null, patientA, doctorA, departmentA);
        body.put("hospitalIdentifier", hospitalA.getCode());

        MvcResult refused = send(doctorB, hospitalB, body);

        assertThat(refused.getResponse().getStatus()).isIn(403, 404);
        assertSaysNothingOf(refused, patientA);
        assertThat(encounterRepository.count()).isZero();
    }

    @Test
    @DisplayName("at its own hospital, another hospital's patient answers 404 exactly as an unknown patient does")
    void anotherHospitalsPatientIsUnknown() throws Exception {
        UUID unknown = UUID.randomUUID();
        MvcResult foreign = send(doctorB, hospitalB, visit(hospitalB.getId(), patientA, doctorB, departmentB));
        Map<String, Object> missingBody = visit(hospitalB.getId(), patientA, doctorB, departmentB);
        missingBody.put("patientId", unknown);
        MvcResult missing = send(doctorB, hospitalB, missingBody);

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(foreign).replace(patientA.getId().toString(), "<id>"))
            .isEqualTo(message(missing).replace(unknown.toString(), "<id>"));
        assertSaysNothingOf(foreign, patientA);
        assertThat(encounterRepository.count()).isZero();
    }

    @Test
    @DisplayName("at its own hospital, a clinician of another hospital answers 404 and no visit is created")
    void anotherHospitalsClinicianIsUnknown() throws Exception {
        MvcResult refused = send(doctorB, hospitalB, visit(hospitalB.getId(), patientB, doctorA, departmentB));

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(refused.getResponse().getContentAsString())
            .doesNotContain(doctorA.user().getEmail(), doctorA.user().getPhoneNumber());
        assertThat(encounterRepository.count()).isZero();
    }

    @Test
    @DisplayName("each doctor still opens a visit at their own hospital")
    void aVisitAtTheCallersOwnHospitalIsCreated() throws Exception {
        MvcResult atA = send(doctorA, hospitalA, visit(hospitalA.getId(), patientA, doctorA, departmentA));
        MvcResult atB = send(doctorB, hospitalB, visit(hospitalB.getId(), patientB, doctorB, departmentB));

        assertThat(atA.getResponse().getStatus()).as(atA.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(atB.getResponse().getStatus()).as(atB.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(encounterRepository.count()).isEqualTo(2);
        assertThat(visitsOf(patientA, hospitalA)).isEqualTo(1);
        assertThat(visitsOf(patientB, hospitalB)).isEqualTo(1);
    }

    @Test
    @DisplayName("a doctor holding both hospitals opens the visit at the hospital the body names; it is audited there")
    void aDoctorOfBothHospitalsActsAtTheHospitalNamed() throws Exception {
        // B's doctor also holds a role at A and has A selected; the visit names B, where they practise.
        alsoAssign(doctorB.user(), hospitalA);

        MvcResult created = mockMvc.perform(post(API + "/encounters")
                .with(acting(doctorB.user(), hospitalA.getId(), Set.of(hospitalA.getId(), hospitalB.getId())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    visit(hospitalB.getId(), patientB, doctorB, departmentB))))
            .andReturn();

        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(visitsOf(patientB, hospitalB)).isEqualTo(1);
        assertThat(writeAuditHospitals(doctorB.user()))
            .as("the write is audited at the hospital it was made at")
            .containsOnly(hospitalB.getId());
    }

    @Test
    @DisplayName("a doctor of B who is a patient at A cannot open a visit at A (403); at B they still can")
    void aDoctorWhoIsOnlyAPatientAtAHospitalCannotWriteThere() throws Exception {
        // r81 review: B's doctor is registered as a patient at A, which gives a PATIENT role there.
        alsoAssign(doctorB.user(), hospitalA, "ROLE_PATIENT");

        MvcResult refused = mockMvc.perform(post(API + "/encounters")
                .with(actingLive(doctorB.user()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(visit(hospitalA.getId(), patientA, doctorA, departmentA))))
            .andReturn();
        MvcResult created = mockMvc.perform(post(API + "/encounters")
                .with(actingLive(doctorB.user()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(visit(hospitalB.getId(), patientB, doctorB, departmentB))))
            .andReturn();

        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isEqualTo(403);
        assertSaysNothingOf(refused, patientA);
        assertThat(visitsOf(patientA, hospitalA)).isZero();
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(visitsOf(patientB, hospitalB)).isEqualTo(1);
    }

    @Test
    @DisplayName("naming the hospital where they are only a patient: their own opt-out passes, a visit there is 403")
    void theHeaderOfAPatientOnlyHospitalIsIgnoredOnAWrite() throws Exception {
        // r81 review round 2: B's doctor is a patient at A, and the session made A their selected hospital.
        alsoAssign(doctorB.user(), hospitalA, "ROLE_PATIENT");
        Patient own = savePatient("Bakary", "Selfcare", hospitalA, doctorB.user());

        // The auth filters answer 403 for a refused header before any handler runs; this IT runs
        // without them, so their decision is asserted here: the header is ignored, not refused.
        org.springframework.mock.web.MockHttpServletRequest write =
            new org.springframework.mock.web.MockHttpServletRequest("POST", API + "/patients/x/record-sharing/opt-out");
        write.setContextPath(API);
        write.addHeader("X-Hospital-Id", hospitalA.getId().toString());
        HospitalContext applied = actingScopeResolver.withHeader(
            actingScopeResolver.liveContext(doctorB.user().getId(), doctorB.user().getUsername()), write);
        assertThat(com.example.hms.security.tenant.ActingScopeResolver.isRefusedHeader(applied))
            .as("a staff write naming a hospital where the caller is only a patient is not refused at the filter")
            .isFalse();

        MvcResult optOut = mockMvc.perform(post(API + "/patients/{id}/record-sharing/opt-out", own.getId())
                .with(actingLive(doctorB.user(), hospitalA.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();
        MvcResult visitAtA = mockMvc.perform(post(API + "/encounters")
                .with(actingLive(doctorB.user(), hospitalA.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(visit(hospitalA.getId(), patientA, doctorA, departmentA))))
            .andReturn();
        MvcResult visitUnnamed = mockMvc.perform(post(API + "/encounters")
                .with(actingLive(doctorB.user(), hospitalA.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(visit(null, patientA, doctorA, departmentA))))
            .andReturn();

        assertThat(optOut.getResponse().getStatus()).as(optOut.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(visitAtA.getResponse().getStatus()).as(visitAtA.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(visitUnnamed.getResponse().getStatus()).as(visitUnnamed.getResponse().getContentAsString())
            .isIn(403, 404);
        assertSaysNothingOf(visitAtA, patientA);
        assertSaysNothingOf(visitUnnamed, patientA);
        assertThat(visitsOf(patientA, hospitalA)).isZero();
    }

    /** The caller's context as the auth filters build it, from their live assignments, no header. */
    private RequestPostProcessor actingLive(User user) {
        return actingLive(user, null);
    }

    /** The same, with {@code X-Hospital-Id} applied as the auth filters apply it. */
    private RequestPostProcessor actingLive(User user, UUID header) {
        return request -> {
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR),
                new SimpleGrantedAuthority("ROLE_PATIENT"));
            CustomUserDetails principal = new CustomUserDetails(
                user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.clear();
            request.setContextPath(API);
            if (header != null) {
                request.addHeader("X-Hospital-Id", header.toString());
            }
            HospitalContextHolder.setContext(actingScopeResolver.withHeader(
                actingScopeResolver.liveContext(user.getId(), user.getUsername()), request));
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private long visitsOf(Patient patient, Hospital hospital) {
        return encounterRepository.findByPatient_IdAndHospital_Id(patient.getId(), hospital.getId(),
            org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
    }

    /* --------------------------------------------------------------------- */

    private MvcResult send(Actor actor, Hospital actingAt, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(API + "/encounters")
                .with(acting(actor.user(), actingAt.getId(), Set.of(actingAt.getId())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andReturn();
    }

    private Map<String, Object> visit(UUID hospitalId, Patient patient, Actor clinician, Department department) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientId", patient.getId());
        body.put("staffId", clinician.staff().getId());
        if (hospitalId != null) {
            body.put("hospitalId", hospitalId);
        }
        body.put("departmentId", department.getId());
        body.put("encounterType", EncounterType.CONSULTATION.name());
        body.put("encounterDate", LocalDateTime.now().minusMinutes(sequence.incrementAndGet() % 50).withNano(0).toString());
        body.put("chiefComplaint", "Consultation de suivi");
        body.put("status", EncounterStatus.IN_PROGRESS.name());
        return body;
    }

    private void assertSaysNothingOf(MvcResult result, Patient patient) throws Exception {
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain(patient.getFirstName(), patient.getLastName(), patient.getPhoneNumberPrimary(),
                patient.getEmail());
    }

    private String message(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("message").asText();
    }

    /** The hospitals of the write-audit rows of this actor's visit creations, waited for briefly. */
    private Set<UUID> writeAuditHospitals(User actor) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Set<UUID> hospitals = new java.util.HashSet<>();
            for (AuditEventLog row : auditEventLogRepository.findAll()) {
                if (row.getUser() != null && actor.getId().equals(row.getUser().getId())
                    && row.getEventDescription() != null && row.getEventDescription().contains("/encounters")) {
                    hospitals.add(row.getHospitalId());
                }
            }
            if (!hospitals.isEmpty()) {
                return hospitals;
            }
            Thread.sleep(100);
        }
        return Set.of();
    }

    private RequestPostProcessor acting(User user, UUID actingHospitalId, Set<UUID> held) {
        return request -> {
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
            CustomUserDetails principal = new CustomUserDetails(
                user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.clear();
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(user.getId())
                .principalUsername(user.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(actingHospitalId)
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(held)
                .build());
            request.setContextPath(API);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + id).code("VS" + id).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226555" + id).email("vs" + id + "@hospital.test")
            .organization(organization).build());
    }

    private Department saveDepartment(Hospital hospital, UserRoleHospitalAssignment assignment) {
        String id = nextId();
        return departmentRepository.saveAndFlush(Department.builder()
            .name("Médecine générale " + id).code("D" + id).email("d" + id + "@dept.test")
            .phoneNumber("+226-111-0000").active(true).hospital(hospital).assignment(assignment).build());
    }

    private Actor actor(String prefix, Hospital hospital) {
        String id = nextId();
        User user = userRepository.save(User.builder()
            .username(prefix + id).passwordHash("hashed-password").email(prefix + id + "@example.test")
            .firstName(prefix).lastName("User" + id).phoneNumber("+22677" + id).isActive(true).build());
        Staff staff = assign(user, hospital);
        return new Actor(user, staff, staff.getAssignment(), hospital);
    }

    /** An active DOCTOR assignment and the staff record of {@code user}, at {@code hospital}. */
    private Staff assign(User user, Hospital hospital) {
        UserRoleHospitalAssignment assignment = alsoAssign(user, hospital);
        return staffRepository.save(Staff.builder()
            .user(user).hospital(hospital).assignment(assignment).jobTitle(JobTitle.DOCTOR)
            .employmentType(EmploymentType.FULL_TIME).licenseNumber("LIC-VS-" + nextId())
            .name(user.getFirstName()).active(true).build());
    }

    /** One more active DOCTOR assignment for {@code user}, at {@code hospital}. */
    private UserRoleHospitalAssignment alsoAssign(User user, Hospital hospital) {
        return alsoAssign(user, hospital, ROLE_DOCTOR);
    }

    /** One more active assignment in {@code roleCode} for {@code user}, at {@code hospital}. */
    private UserRoleHospitalAssignment alsoAssign(User user, Hospital hospital, String roleCode) {
        String name = roleCode.substring("ROLE_".length());
        Role role = roleRepository.findByCode(roleCode)
            .orElseGet(() -> roleRepository.save(Role.builder().name(name).code(roleCode)
                .description(roleCode).build()));
        return assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-VS-" + nextId()).description(name + " at " + hospital.getName())
            .user(user).hospital(hospital).role(role).startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).active(true).build());
    }

    private Patient savePatient(String firstName, String lastName, Hospital hospital) {
        return savePatient(firstName, lastName, hospital, null);
    }

    /** A patient registered at {@code hospital}, whose login is {@code login} (a new account when null). */
    private Patient savePatient(String firstName, String lastName, Hospital hospital, User login) {
        String id = nextId();
        User account = login != null ? login : userRepository.save(User.builder()
            .username("vspatient" + id).passwordHash("hashed-password").email("vspatient" + id + "@patient.test")
            .firstName(firstName).lastName(lastName).phoneNumber("+22676" + id).isActive(true).build());
        Patient saved = patientRepository.save(Patient.builder()
            .firstName(firstName).lastName(lastName + id).dateOfBirth(LocalDate.of(1992, 3, 10)).gender("F")
            .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
            .phoneNumberPrimary("+22678" + id).email(firstName.toLowerCase() + id + "@patient.test")
            .emergencyContactName("Issa Diallo").emergencyContactPhone("+22679" + id)
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(account).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved).hospital(hospital).mrn("MRN-VS-" + id).registrationDate(LocalDate.now())
            .active(true).build());
        return saved;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
