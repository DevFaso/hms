package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D824, the exact re-test call: RECEPTIONIST_A, whose login was linked to a
 * patient record before the D819 fix (legacy data that is still on dev and may
 * be on prod), posts {@code /appointments} with a date, a time, a clinician, the
 * hospital, a department and a reason, but no {@code patientId}. That booked
 * her own linked patient record (201). A staff booking now names its patient:
 * a translated 400, and no appointment is written.
 */
@AutoConfigureMockMvc(addFilters = false)
class StaffBookingPatientRequiredIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private Organization organization;
    private Hospital hospital;
    private Department department;
    private User receptionist;
    private Staff deskStaff;
    private Patient linkedToReceptionist;

    @BeforeEach
    void setUp() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
        organization = organizationRepository.save(Organization.builder()
            .name("D824 Network").code("D824ORG-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("D824 Hospital").code("D824H" + nextId()).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226552" + nextId()).email("d824" + nextId() + "@hospital.test")
            .organization(organization).build());

        receptionist = userRepository.save(user("recep"));
        UserRoleHospitalAssignment desk = assignmentRepository.save(assignment(receptionist, role(ROLE_RECEPTIONIST)));
        department = departmentRepository.save(Department.builder()
            .name("D824 Medicine").code("D824M" + nextId()).email("d824med" + nextId() + "@dept.test")
            .phoneNumber("+226-111-0824").active(true).hospital(hospital).assignment(desk).build());
        deskStaff = staffRepository.save(Staff.builder()
            .user(receptionist).hospital(hospital).assignment(desk).department(department)
            .jobTitle(JobTitle.values()[0]).employmentType(EmploymentType.FULL_TIME)
            .name("Desk " + receptionist.getFirstName()).active(true).build());

        // The legacy D819 row: the receptionist's own login is a patient's login.
        // Written straight to the repository, as it was before the guard existed.
        String suffix = nextId();
        linkedToReceptionist = patientRepository.save(Patient.builder()
            .firstName("Rasmané").lastName("Kaboré").dateOfBirth(LocalDate.of(1990, 4, 2)).gender("M")
            .address("Secteur 15").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("+22670" + suffix).email("d824p" + suffix + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("+22671" + suffix)
            .organizationId(organization.getId()).hospitalId(hospital.getId())
            .user(receptionist).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(linkedToReceptionist).hospital(hospital).mrn("MRN-D824-" + suffix)
            .registrationDate(LocalDate.now()).active(true).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("the re-test call: a staff POST /appointments with no patientId is a translated 400, not a booking for the caller's linked record")
    void aStaffBookingWithoutPatientIdIsRefused() throws Exception {
        long before = appointmentRepository.count();

        mockMvc.perform(post(API + "/appointments").contextPath(API)
                .header("Accept-Language", "fr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithoutPatient())
                .with(asReceptionist()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Choisissez le patient de ce rendez-vous")));

        mockMvc.perform(post(API + "/appointments").contextPath(API)
                .header("Accept-Language", "es")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithoutPatient())
                .with(asReceptionist()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Elija el paciente de esta cita")));

        assertThat(appointmentRepository.count()).isEqualTo(before);
        assertThat(appointmentRepository.findByPatient_Id(linkedToReceptionist.getId())).isEmpty();
    }

    private String bodyWithoutPatient() {
        return """
            {"appointmentDate":"%s","startTime":"09:00","endTime":"09:30","staffId":"%s",
             "hospitalId":"%s","departmentId":"%s","reason":"Consultation"}
            """.formatted(LocalDate.now().plusDays(1), deskStaff.getId(), hospital.getId(), department.getId());
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code.substring(5)).code(code)
                .description(code).build()));
    }

    private UserRoleHospitalAssignment assignment(User user, Role role) {
        return UserRoleHospitalAssignment.builder()
            .assignmentCode("D824-" + nextId()).description("d824").user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder().username("d824" + prefix + suffix).passwordHash("hashed")
            .email("d824" + prefix + suffix + "@example.test").firstName(prefix).lastName("D824" + suffix)
            .phoneNumber("+22677" + suffix).isActive(true).build();
    }

    private RequestPostProcessor asReceptionist() {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_RECEPTIONIST));
        CustomUserDetails principal = new CustomUserDetails(receptionist.getId(), receptionist.getUsername(),
            receptionist.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, receptionist.getPasswordHash(),
            authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(receptionist.getId())
                .principalUsername(receptionist.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(hospital.getId())
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(hospital.getId()))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
