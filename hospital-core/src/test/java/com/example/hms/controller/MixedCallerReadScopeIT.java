package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * r81: a staff member who is also registered as a patient at another hospital reads, as staff,
 * only where they work. Over the real controllers, services and database, with the caller's
 * context built from their real assignments as the auth filters build it.
 *
 * <p>A doctor of hospital B is a patient at hospital A. Naming A (by {@code X-Hospital-Id} or by
 * {@code ?hospitalId=}) does not make a staff read act at A, and the hospitals their unnamed
 * reads cover are those where they work. Their own sharing opt-out still answers, and the scope
 * they act at by default is B.
 */
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("r81: a staff member who is a patient elsewhere reads, as staff, only where they work")
class MixedCallerReadScopeIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private final AtomicInteger sequence = new AtomicInteger(
        java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000, 60_000));

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private ActingScopeResolver actingScopeResolver;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User doctorB;
    private Patient patientA;
    private Patient patientB;
    private Patient ownRecord;

    @BeforeEach
    void setUp() {
        wipe();
        organization = organizationRepository.save(Organization.builder()
            .name("Mixed Caller Network").code("ORG-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN)
            .active(true).build());
        hospitalA = saveHospital("Mixed A");
        hospitalB = saveHospital("Mixed B");
        doctorB = saveUser("mcDoctorB", "Moussa", "Kabore");
        Staff staff = staffRepository.save(Staff.builder()
            .user(doctorB).hospital(hospitalB).assignment(assign(doctorB, hospitalB, ROLE_DOCTOR))
            .jobTitle(JobTitle.DOCTOR).employmentType(EmploymentType.FULL_TIME).licenseNumber("LIC-MC-" + nextId())
            .name(doctorB.getFirstName()).active(true).build());
        assertThat(staff.getId()).isNotNull();
        // Registered as a patient at A: a PATIENT role there and their own record.
        assign(doctorB, hospitalA, ROLE_PATIENT);
        ownRecord = savePatient("Moussa", "Kabore", hospitalA, doctorB);
        patientA = savePatient("Aminata", "Readscope", hospitalA, null);
        patientB = savePatient("Binta", "Readscope", hospitalB, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        wipe();
    }

    private void wipe() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("the hospital acted at by default is the one where they work, not the one where they are treated")
    void theDefaultScopeIsWhereTheyWork() {
        HospitalContext live = actingScopeResolver.liveContext(doctorB.getId(), doctorB.getUsername());

        assertThat(ActingScopeResolver.scopeOf(live))
            .isEqualTo(new ActingScope.Pinned(hospitalB.getId(), ActingScope.Source.DEFAULT));
    }

    @Test
    @DisplayName("X-Hospital-Id naming A is ignored on a staff read: A's patient is not read")
    void theHeaderOfThePatientHospitalIsIgnoredOnARead() throws Exception {
        MvcResult byId = mockMvc.perform(get(API + "/patients/{id}", patientA.getId()).with(as(hospitalA.getId())))
            .andReturn();
        MvcResult list = mockMvc.perform(get(API + "/patients").with(as(hospitalA.getId()))).andReturn();

        assertThat(byId.getResponse().getStatus()).as(byId.getResponse().getContentAsString()).isIn(403, 404);
        assertSaysNothingOf(byId, patientA);
        assertThat(list.getResponse().getStatus()).as(list.getResponse().getContentAsString()).isEqualTo(200);
        assertSaysNothingOf(list, patientA);
        assertSaysNothingOf(list, ownRecord);
        assertThat(list.getResponse().getContentAsString()).doesNotContain(patientA.getId().toString());
    }

    @Test
    @DisplayName("?hospitalId=A on a staff read is refused (403)")
    void aParameterNamingThePatientHospitalIsRefused() throws Exception {
        MvcResult refused = mockMvc.perform(get(API + "/patients/{id}", patientA.getId())
                .param("hospitalId", hospitalA.getId().toString()).with(as(null)))
            .andReturn();

        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isEqualTo(403);
        assertSaysNothingOf(refused, patientA);
    }

    @Test
    @DisplayName("at B they still read B's patient, and their own sharing opt-out answers")
    void theirOwnHospitalAndTheirOwnOptOutStillAnswer() throws Exception {
        MvcResult atB = mockMvc.perform(get(API + "/patients/{id}", patientB.getId()).with(as(null))).andReturn();
        MvcResult optOut = mockMvc.perform(get(API + "/patients/{id}/record-sharing/opt-out", ownRecord.getId())
                .with(as(hospitalA.getId())))
            .andReturn();

        assertThat(atB.getResponse().getStatus()).as(atB.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(optOut.getResponse().getStatus()).as(optOut.getResponse().getContentAsString()).isEqualTo(200);
    }

    @Test
    @DisplayName("the hospitals a staff read covers leave out A; the patient portal keeps it")
    void theReadableHospitalsLeaveOutThePatientHospital() {
        HospitalContext live = actingScopeResolver.liveContext(doctorB.getId(), doctorB.getUsername());
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(request("/patients")));
        try {
            assertThat(ActingScopeResolver.readableHospitalIds(live)).containsExactly(hospitalB.getId());
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request("/me/patient/profile")));
            assertThat(ActingScopeResolver.readableHospitalIds(live))
                .containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    /* --------------------------------------------------------------------- */

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", API + path);
        request.setContextPath(API);
        return request;
    }

    /** As doctorB, the context built from their live assignments, with {@code header} applied when given. */
    private RequestPostProcessor as(UUID header) {
        return request -> {
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR),
                new SimpleGrantedAuthority(ROLE_PATIENT));
            CustomUserDetails principal = new CustomUserDetails(
                doctorB.getId(), doctorB.getUsername(), doctorB.getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal, doctorB.getPasswordHash(),
                authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setContextPath(API);
            if (header != null) {
                request.addHeader("X-Hospital-Id", header.toString());
            }
            HospitalContextHolder.clear();
            HospitalContext context = actingScopeResolver.withHeader(
                actingScopeResolver.liveContext(doctorB.getId(), doctorB.getUsername()), request);
            // The auth filters answer 403 for a refused header before any handler; this IT runs
            // without them, so it is asserted here: the header is never refused, only ignored.
            assertThat(ActingScopeResolver.isRefusedHeader(context)).isFalse();
            HospitalContextHolder.setContext(context);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private void assertSaysNothingOf(MvcResult result, Patient patient) throws Exception {
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain(patient.getLastName(), patient.getPhoneNumberPrimary(), patient.getEmail());
    }

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name + " " + id).code("MC" + id).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226554" + id).email("mc" + id + "@hospital.test")
            .organization(organization).build());
    }

    private User saveUser(String prefix, String firstName, String lastName) {
        String id = nextId();
        return userRepository.save(User.builder()
            .username(prefix + id).passwordHash("hashed-password").email(prefix + id + "@example.test")
            .firstName(firstName).lastName(lastName + id).phoneNumber("+22673" + id).isActive(true).build());
    }

    private UserRoleHospitalAssignment assign(User user, Hospital hospital, String roleCode) {
        String name = roleCode.substring("ROLE_".length());
        Role role = roleRepository.findByCode(roleCode)
            .orElseGet(() -> roleRepository.save(Role.builder().name(name).code(roleCode)
                .description(roleCode).build()));
        return assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-MC-" + nextId()).description(name + " at " + hospital.getName())
            .user(user).hospital(hospital).role(role).startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).active(true).build());
    }

    /** A patient registered at {@code hospital}, whose login is {@code login} (a new account when null). */
    private Patient savePatient(String firstName, String lastName, Hospital hospital, User login) {
        String id = nextId();
        User account = login != null ? login : saveUser("mcpatient", firstName, lastName);
        Patient saved = patientRepository.save(Patient.builder()
            .firstName(firstName).lastName(lastName + id).dateOfBirth(LocalDate.of(1990, 5, 2)).gender("F")
            .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
            .phoneNumberPrimary("+22675" + id).email(firstName.toLowerCase() + id + "@patient.test")
            .emergencyContactName("Issa Diallo").emergencyContactPhone("+22679" + id)
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(account).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved).hospital(hospital).mrn("MRN-MC-" + id).registrationDate(LocalDate.now())
            .active(true).build());
        return saved;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
