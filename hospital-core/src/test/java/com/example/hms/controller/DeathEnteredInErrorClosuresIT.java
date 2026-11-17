package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InBasketItemRepository;
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
import com.jayway.jsonpath.JsonPath;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1516 over HTTP, on the real stack: a death that closes an admitted stay and a visit, then marked entered in
 * error. The stay is no longer DECEASED: it is CLOSED_IN_ERROR, ended when the closure ran; the visit stays closed
 * (nothing is reopened, #1068); an urgent item is filed to the stay's attending; and the chart reads what was closed.
 */
@AutoConfigureMockMvc(addFilters = false)
class DeathEnteredInErrorClosuresIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";

    private final AtomicInteger sequence = new AtomicInteger();

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
    @Autowired private AdmissionRepository admissionRepository;
    @Autowired private DeathRecordRepository deathRecordRepository;
    @Autowired private InBasketItemRepository inBasketItemRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Organization organization;
    private Hospital hospital;
    private User doctorUser;
    private Staff doctor;
    private User adminUser;
    private Patient patient;
    private Admission stay;
    private Encounter visit;
    private final List<User> users = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Voided Death Network").code("ORG-VDC-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Voided Death Hospital").code("HVD" + nextId())
            .city("Bobo-Dioulasso").country("Burkina Faso").address("2 Main St")
            .phoneNumber("+226558" + nextId()).email("vdc" + nextId() + "@hospital.test")
            .organization(organization).build());
        doctorUser = userRepository.save(buildUser("doctor"));
        doctor = staffAt(doctorUser, role(ROLE_DOCTOR, "Doctor"), JobTitle.PHYSICIAN);
        adminUser = userRepository.save(buildUser("admin"));
        User patientUser = userRepository.save(buildUser("patient"));
        users.addAll(List.of(doctorUser, adminUser, patientUser));
        staffAt(adminUser, role(ROLE_HOSPITAL_ADMIN, "Hospital admin"), JobTitle.PHYSICIAN);

        patient = patientRepository.save(Patient.builder()
            .firstName("Salif").lastName("Kaboré")
            .dateOfBirth(LocalDate.now().minusYears(50)).gender("M")
            .address("Patient address").city("Bobo-Dioulasso").country("Burkina Faso")
            .phoneNumberPrimary("+22679" + nextId()).email("sk" + nextId() + "@patient.test")
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser)
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("VDC" + nextId())
            .registrationDate(LocalDate.now()).active(true).build());
        Admission admission = new Admission();
        admission.setPatient(patient);
        admission.setHospital(hospital);
        admission.setAdmittingProvider(doctor);
        admission.setAttendingPhysician(doctor);
        admission.setAdmissionType(AdmissionType.EMERGENCY);
        admission.setAcuityLevel(AcuityLevel.LEVEL_3_MAJOR);
        admission.setStatus(AdmissionStatus.ACTIVE);
        admission.setChiefComplaint("Fièvre");
        admission.setAdmissionDateTime(LocalDateTime.now().minusDays(2));
        // The JSON lists are left unset: this test database reads an empty one back as a string.
        admission.setSecondaryDiagnoses(null);
        admission.setConsultingPhysicians(null);
        admission.setCustomOrders(null);
        admission.setFollowUpAppointments(null);
        stay = admissionRepository.save(admission);
        visit = encounterRepository.save(Encounter.builder()
            .patient(patient).staff(doctor).hospital(hospital).assignment(doctor.getAssignment())
            .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now().minusHours(3))
            .status(EncounterStatus.IN_PROGRESS).code("ENC-VDC-" + nextId()).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // Every row this test made, children first: this H2 database is shared, and a row left here (the death
        // record, the invoice the visit's checkout bills) fails the next class's wipe on its foreign key.
        UUID patientId = patient.getId();
        UUID hospitalId = hospital.getId();
        jdbc.update("DELETE FROM clinical.in_basket_items WHERE patient_id = ? OR hospital_id = ?", patientId, hospitalId);
        jdbc.update("DELETE FROM clinical.order_review_holds WHERE patient_id = ?", patientId);
        jdbc.update("DELETE FROM billing.invoice_items WHERE billing_invoice_id IN "
            + "(SELECT id FROM billing.billing_invoices WHERE patient_id = ? OR hospital_id = ?)", patientId, hospitalId);
        jdbc.update("DELETE FROM billing.billing_invoices WHERE patient_id = ? OR hospital_id = ?", patientId, hospitalId);
        jdbc.update("DELETE FROM clinical.death_records WHERE patient_id = ?", patientId);
        jdbc.update("DELETE FROM clinical.encounter_history WHERE encounter_id IN "
            + "(SELECT id FROM clinical.encounters WHERE patient_id = ?)", patientId);
        jdbc.update("DELETE FROM support.audit_event_logs WHERE patient_id = ? OR hospital_id = ? OR assignment_id IN "
            + "(SELECT id FROM \"security\".user_role_hospital_assignment WHERE hospital_id = ?)", patientId, hospitalId, hospitalId);
        users.forEach(u -> jdbc.update("DELETE FROM support.audit_event_logs WHERE user_id = ?", u.getId()));
        encounterRepository.deleteAll(encounterRepository.findByPatient_Id(patientId));
        admissionRepository.deleteAll(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId));
        registrationRepository.deleteAll(registrationRepository.findAll().stream()
            .filter(r -> r.getPatient() != null && patientId.equals(r.getPatient().getId())).toList());
        patientRepository.deleteById(patientId);
        staffRepository.deleteAll(staffRepository.findAll().stream()
            .filter(s -> s.getHospital() != null && hospitalId.equals(s.getHospital().getId())).toList());
        assignmentRepository.deleteAll(assignmentRepository.findAll().stream()
            .filter(a -> a.getHospital() != null && hospitalId.equals(a.getHospital().getId())).toList());
        users.forEach(u -> userRepository.deleteById(u.getId()));
        hospitalRepository.deleteById(hospitalId);
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    @DisplayName("QA D1516: a voided death leaves its stay flagged, not DECEASED, files the review and shows on the chart")
    void aVoidedDeathLeavesItsStayFlaggedFilesTheReviewAndShowsOnTheChart() throws Exception {
        String recorded = mockMvc.perform(post(API + "/mortality/deaths").contextPath(API).with(as(doctorUser, ROLE_DOCTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"" + patient.getId() + "\",\"diedAt\":\"" + LocalDateTime.now().minusHours(1).withNano(0)
                    + "\",\"immediateCause\":\"Choc septique\"}"))
            .andExpect(status().is2xxSuccessful())
            .andExpect(jsonPath("$.closure.admissionsClosed").value(1))
            .andExpect(jsonPath("$.closure.encountersClosed").value(1))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID recordId = UUID.fromString(JsonPath.read(recorded, "$.deathRecord.id"));
        assertThat(admissionRepository.findById(stay.getId()).orElseThrow().getStatus()).isEqualTo(AdmissionStatus.DECEASED);
        LocalDateTime closedAt = deathRecordRepository.findById(recordId).orElseThrow().getCreatedAt();

        mockMvc.perform(post(API + "/mortality/deaths/{id}/entered-in-error", recordId).contextPath(API)
                .with(as(adminUser, ROLE_HOSPITAL_ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Mauvais patient\"}"))
            .andExpect(status().isOk());

        Admission after = admissionRepository.findById(stay.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AdmissionStatus.CLOSED_IN_ERROR);
        assertThat(after.getActualDischargeDateTime()).isEqualTo(closedAt);
        assertThat(after.getBed()).isNull();
        assertThat(encounterRepository.findById(visit.getId()).orElseThrow().getStatus())
            .as("nothing is reopened").isEqualTo(EncounterStatus.COMPLETED);
        assertThat(patientRepository.findById(patient.getId()).orElseThrow().isDeceased()).isFalse();

        List<InBasketItem> items = inBasketItemRepository.findAll().stream()
            .filter(item -> item.getPatient() != null && patient.getId().equals(item.getPatient().getId()))
            .filter(item -> "DEATH_ENTERED_IN_ERROR_CLOSURES".equals(item.getReferenceType()))
            .toList();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getRecipientUser().getId()).isEqualTo(doctorUser.getId());
        assertThat(items.get(0).getHospital().getId()).isEqualTo(hospital.getId());
        assertThat(items.get(0).getPriority()).isEqualTo(InBasketPriority.URGENT);

        mockMvc.perform(get(API + "/mortality/patients/{id}/voided-closures", patient.getId()).contextPath(API)
                .with(as(doctorUser, ROLE_DOCTOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].deathRecordId").value(recordId.toString()))
            .andExpect(jsonPath("$[0].stays[0].admissionId").value(stay.getId().toString()))
            .andExpect(jsonPath("$[0].visits[0].encounterId").value(visit.getId().toString()));
    }

    private Role role(String code, String name) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(name).code(code).description(name).build()));
    }

    private Staff staffAt(User user, Role role, JobTitle title) {
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId()).description(role.getName())
            .user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        Staff staff = staffRepository.save(Staff.builder()
            .user(user).hospital(hospital).assignment(assignment)
            .jobTitle(title).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId()).name("Dr. " + user.getFirstName()).active(true).build());
        return staff;
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + "-vdc-" + suffix).passwordHash("hashed-password")
            .email(prefix + suffix + "@voided.test").firstName(prefix + "FN").lastName("User" + suffix)
            .phoneNumber("+22676" + suffix).isActive(true).build();
    }

    private RequestPostProcessor as(User user, String roleCode) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(roleCode));
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
        return String.valueOf(System.nanoTime() % 1_000_000_000L + sequence.incrementAndGet());
    }
}
