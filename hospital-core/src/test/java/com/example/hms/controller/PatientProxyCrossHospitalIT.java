package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1212 (rounds 23 and 24), through the real chain, the real transaction
 * manager and the real database: EVERY proxy endpoint, for a grantor
 * registered only at another hospital than the proxy's and for one at the
 * same hospital, with and without the proxy naming their own hospital.
 *
 * <p>Round 23 fixed four endpoints by unit tests with mocked services; the
 * fifth ({@code /records}) caught a nested transactional read's exception,
 * which had already marked the request's transaction rollback-only, and its
 * commit answered 500 (UnexpectedRollbackException). Mocks have no
 * transaction, so only a test like this one can see that class.
 */
@AutoConfigureMockMvc
class PatientProxyCrossHospitalIT extends BaseIT {

    private static final String API = "/api";
    private static final String PROXY_BASE = "/api/me/patient/proxy-access/{patientId}/";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private PatientProxyRepository proxyRepository;
    @Autowired private ImmunizationRepository immunizationRepository;

    private Hospital hospitalA;
    private User proxyUser;
    private Patient grantorAtB;
    private Patient grantorAtA;

    @BeforeEach
    void seed() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);

        Organization organization = organizationRepository.save(Organization.builder()
            .name("Proxy Network").code("ORG-PX" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospitalA = hospital(organization, "Hopital A");
        Hospital hospitalB = hospital(organization, "Hopital B");

        Role patientRole = roleRepository.save(Role.builder()
            .name("Patient").code(ROLE_PATIENT).description("Patient role").build());

        // The proxy: a patient of hospital A, with the hospital-bound PATIENT row.
        proxyUser = userRepository.save(user("proxy"));
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-PX" + nextId()).description("Patient at A")
            .user(proxyUser).hospital(hospitalA).role(patientRole)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        register(patient(proxyUser, organization, hospitalA, "Salif"), hospitalA);

        // Grantor 1: registered ONLY at hospital B (B1 in the QA report).
        grantorAtB = patient(userRepository.save(user("grantb")), organization, hospitalB, "Rasmata");
        register(grantorAtB, hospitalB);
        immunizationRepository.save(PatientImmunization.builder()
            .patient(grantorAtB).hospital(hospitalB)
            .vaccineDisplay("BCG").administrationDate(LocalDate.now().minusYears(1))
            .status("COMPLETED").build());
        grant(grantorAtB);

        // Grantor 2: registered at the proxy's own hospital (the A1/A3 control).
        grantorAtA = patient(userRepository.save(user("granta")), organization, hospitalA, "Awa");
        register(grantorAtA, hospitalA);
        grant(grantorAtA);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        proxyRepository.deleteAll();
        immunizationRepository.deleteAll();
    }

    /** Every proxy endpoint the portal and the apps call, by the scope it needs. */
    static Stream<Arguments> everyProxyEndpoint() {
        return Stream.of("records", "appointments", "medications", "lab-results", "lab-results?offset=0&limit=10",
                "billing")
            .flatMap(endpoint -> Stream.of(
                Arguments.of(endpoint, true, false),
                Arguments.of(endpoint, true, true),
                Arguments.of(endpoint, false, false),
                Arguments.of(endpoint, false, true)));
    }

    @ParameterizedTest(name = "{0} — grantor at another hospital: {1}, proxy names its hospital: {2}")
    @MethodSource("everyProxyEndpoint")
    @DisplayName("QA D1212: every proxy endpoint answers 200 for a cross-hospital and a same-hospital grantor")
    void everyProxyEndpointReadsTheGrantor(String endpoint, boolean grantorElsewhere, boolean namesHospital)
            throws Exception {
        Patient grantor = grantorElsewhere ? grantorAtB : grantorAtA;
        MockHttpServletRequestBuilder request = get(PROXY_BASE + endpoint, grantor.getId())
            .contextPath(API).with(signedIn(proxyUser));
        if (namesHospital) {
            request = request.header("X-Hospital-Id", hospitalA.getId().toString());
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "proxy names its hospital: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("QA D1212: the cross-hospital grantor's summary carries their own record, not empty cards")
    void theCrossHospitalSummaryIsTheGrantorsRecord(boolean namesHospital) throws Exception {
        MockHttpServletRequestBuilder request = get(PROXY_BASE + "records", grantorAtB.getId())
            .contextPath(API).with(signedIn(proxyUser));
        if (namesHospital) {
            request = request.header("X-Hospital-Id", hospitalA.getId().toString());
        }
        mockMvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.profile.firstName").value("Rasmata"))
            .andExpect(jsonPath("$.data.profile.hospitalName").value("Hopital B"))
            .andExpect(jsonPath("$.data.immunizations[0].vaccineDisplay").value("BCG"));
    }

    private void grant(Patient grantor) {
        proxyRepository.save(PatientProxy.builder()
            .grantorPatient(grantor).proxyUser(proxyUser)
            .relationship(ProxyRelationship.SIBLING).status(ProxyStatus.ACTIVE)
            .permissions("ALL").build());
    }

    private Hospital hospital(Organization organization, String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name).code("HPX" + id).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226551" + id).email("px" + id + "@hospital.test")
            .organization(organization).build());
    }

    private Patient patient(User user, Organization organization, Hospital hospital, String firstName) {
        String suffix = nextId();
        return patientRepository.save(Patient.builder()
            .firstName(firstName).lastName("Proxy" + suffix)
            .dateOfBirth(LocalDate.of(1990, 1, 1)).gender("F")
            .address("Patient address").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("+22670" + suffix).email("pt" + suffix + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("+22671" + suffix)
            .organizationId(organization.getId()).hospitalId(hospital.getId())
            .user(user).build());
    }

    private void register(Patient patient, Hospital hospital) {
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("MRN-PX" + nextId())
            .registrationDate(LocalDate.now()).active(true).build());
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix).passwordHash("hashed-password")
            .email(prefix + suffix + "@example.test")
            .firstName(prefix + "FN").lastName("User" + suffix)
            .phoneNumber("+22672" + suffix).isActive(true).build();
    }

    private static RequestPostProcessor signedIn(User user) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_PATIENT));
        CustomUserDetails details = new CustomUserDetails(user.getId(), user.getUsername(), "n/a", true, authorities);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, authorities));
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
