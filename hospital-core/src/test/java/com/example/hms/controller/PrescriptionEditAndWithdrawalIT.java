package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1147 and D1161 (round 21) over HTTP with the real transaction manager:
 * an order edited to another drug is named after that drug wherever the
 * display name is read, and a colleague's withdrawal of a signed order is
 * refused without a reason, accepted with one, and audited as a colleague's.
 */
@AutoConfigureMockMvc(addFilters = false)
class PrescriptionEditAndWithdrawalIT extends BaseIT {

    private static final String API_CONTEXT = "/api";
    @SuppressWarnings("java:S1075")
    private static final String UPDATE_PATH = "/prescriptions/{id}";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

    private final AtomicInteger sequence = new AtomicInteger();
    private final List<UserRoleHospitalAssignment> assignments = new ArrayList<>();
    private final List<Staff> staffRows = new ArrayList<>();
    private final List<User> users = new ArrayList<>();

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    private Organization organization;
    private Hospital hospital;
    private Role doctorRole;
    private User authorUser;
    private Staff authorStaff;
    private User colleagueUser;
    private User patientUser;
    private Patient patient;
    private PatientHospitalRegistration registration;
    private Encounter encounter;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Withdrawal Network").code("ORG-WDR-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Withdrawal Hospital").code("HWD" + nextId())
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226557" + nextId()).email("wdr" + nextId() + "@hospital.test")
            .organization(organization).build());
        doctorRole = roleRepository.findByCode(ROLE_DOCTOR)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("Doctor").code(ROLE_DOCTOR).description("Doctor role").build()));

        authorUser = userRepository.save(buildUser("author"));
        authorStaff = doctorAt(authorUser);
        colleagueUser = userRepository.save(buildUser("colleague"));
        doctorAt(colleagueUser);

        patientUser = userRepository.save(buildUser("patient"));
        users.addAll(List.of(authorUser, colleagueUser, patientUser));
        patient = patientRepository.save(Patient.builder()
            .firstName("Fatimata").lastName("Zongo")
            .dateOfBirth(LocalDate.now().minusYears(35)).gender("F")
            .address("Patient address").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("+22678" + nextId()).email("fz" + nextId() + "@patient.test")
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser)
            .build());
        registration = registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("WDR" + nextId())
            .registrationDate(LocalDate.now()).active(true).build());
        encounter = encounterRepository.save(Encounter.builder()
            .patient(patient).staff(authorStaff).hospital(hospital).assignment(authorStaff.getAssignment())
            .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
            .code("ENC-" + nextId()).build());
        prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patient).staff(authorStaff).encounter(encounter).hospital(hospital)
            .assignment(authorStaff.getAssignment())
            .medicationName("Amoxicilline 500 mg").medicationDisplayName("Amoxicilline 500 mg")
            .dosage("500 mg").frequency("3x/day").duration("7 days")
            .quantity(BigDecimal.TEN).quantityUnit("gélule")
            .status(PrescriptionStatus.DRAFT)
            .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        auditEventLogRepository.deleteAllInBatch(auditEventLogRepository.findAll().stream()
            .filter(row -> prescription.getId().toString().equals(row.getResourceId()))
            .toList());
        prescriptionRepository.deleteById(prescription.getId());
        encounterRepository.deleteById(encounter.getId());
        registrationRepository.deleteById(registration.getId());
        patientRepository.deleteById(patient.getId());
        staffRows.forEach(s -> staffRepository.deleteById(s.getId()));
        assignments.forEach(a -> assignmentRepository.deleteById(a.getId()));
        users.forEach(u -> userRepository.deleteById(u.getId()));
        hospitalRepository.deleteById(hospital.getId());
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    @DisplayName("QA D1147: an order edited to another drug is named after it, display name included")
    void anEditToAnotherDrugRenamesTheOrder() throws Exception {
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Paracétamol 500 mg", null, null)))
            .andExpect(status().isOk());

        Prescription stored = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(stored.getMedicationName()).isEqualTo("Paracétamol 500 mg");
        assertThat(stored.getMedicationDisplayName())
            .as("the list, the pharmacist's view and the record PDF read this copy")
            .isEqualTo("Paracétamol 500 mg");
    }

    @Test
    @DisplayName("QA D1161: a colleague withdraws a signed order only with a reason, and is audited as a colleague")
    void aColleaguesWithdrawalOfASignedOrderNeedsAReasonAndIsAudited() throws Exception {
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription = prescriptionRepository.save(prescription);

        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(colleagueUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(prescription.getMedicationName(), "DISCONTINUED", " ")))
            .andExpect(status().isBadRequest());
        assertThat(prescriptionRepository.findById(prescription.getId()).orElseThrow().getStatus())
            .isEqualTo(PrescriptionStatus.SIGNED);

        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(colleagueUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(prescription.getMedicationName(), "DISCONTINUED", "Allergie apparue, arrêt par le médecin de garde")))
            .andExpect(status().isOk());

        Prescription stored = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PrescriptionStatus.DISCONTINUED);
        assertThat(stored.getWithdrawalReason()).isEqualTo("Allergie apparue, arrêt par le médecin de garde");
        List<AuditEventLog> rows = auditEventLogRepository.findAll().stream()
            .filter(row -> prescription.getId().toString().equals(row.getResourceId()))
            .filter(row -> row.getEventType() == AuditEventType.PRESCRIPTION_DISCONTINUED)
            .toList();
        assertThat(rows).hasSize(1);
        assertThat(String.valueOf(rows.get(0).getDetails())).contains("\"byPrescriber\":false")
            .doesNotContain("Allergie");
    }

    private String body(String medicationName, String status, String withdrawalReason) {
        StringBuilder json = new StringBuilder("{")
            .append("\"patientId\":\"").append(patient.getId()).append("\",")
            .append("\"staffId\":\"").append(authorStaff.getId()).append("\",")
            .append("\"encounterId\":\"").append(encounter.getId()).append("\",")
            .append("\"medicationName\":\"").append(medicationName).append("\",")
            .append("\"dosage\":\"500 mg\",\"frequency\":\"3x/day\",\"duration\":\"7 days\"");
        if (status != null) {
            json.append(",\"status\":\"").append(status).append("\"");
        }
        if (withdrawalReason != null) {
            json.append(",\"withdrawalReason\":\"").append(withdrawalReason).append("\"");
        }
        return json.append("}").toString();
    }

    private Staff doctorAt(User user) {
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId()).description("Doctor assignment")
            .user(user).hospital(hospital).role(doctorRole)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        assignments.add(assignment);
        Staff staff = staffRepository.save(Staff.builder()
            .user(user).hospital(hospital).assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId()).name("Dr. " + user.getFirstName()).active(true).build());
        staffRows.add(staff);
        return staff;
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + "-wdr-" + suffix).passwordHash("hashed-password")
            .email(prefix + suffix + "@withdrawal.test").firstName(prefix + "FN").lastName("User" + suffix)
            .phoneNumber("+22677" + suffix).isActive(true).build();
    }

    private RequestPostProcessor as(User user) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
        CustomUserDetails principal = new CustomUserDetails(
            user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        UUID hospitalId = hospital.getId();
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(user.getId())
                .principalUsername(user.getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(hospitalId)
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(hospitalId))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
