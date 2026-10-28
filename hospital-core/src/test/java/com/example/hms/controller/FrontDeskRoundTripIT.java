package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.RecallStatus;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
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
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA round 16, the front-desk calls of the report replayed on a real
 * persistence context, as RECEPTIONIST_A (the evidence scripts drive dev; this
 * is their local equivalent). Each test is the exact QA call and a round trip:
 * the write, then a fresh read of what was stored.
 *
 * <ul>
 *   <li>D923: {@code PUT /appointments/{id}} answered 200 and stored nothing.</li>
 *   <li>D937: a cancelled booking kept blocking its slot.</li>
 *   <li>D946: a past SCHEDULED appointment could not be marked absent.</li>
 *   <li>D985: booking the visit a recall asks for left the recall PENDING.</li>
 *   <li>D925: {@code PUT /patient-insurances/link} answered 500 for every new policy.</li>
 *   <li>D926: the desk could not correct demographics or record a guardian.</li>
 *   <li>D924, D942, D943: duplicate check, registration match and search missed
 *       other phone formats, accents and a different phone.</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
class FrontDeskRoundTripIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

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
    @Autowired private PatientInsuranceRepository insuranceRepository;
    @Autowired private PatientRecallRepository recallRepository;
    @Autowired private com.example.hms.repository.EncounterRepository encounterRepository;

    private Organization organization;
    private Hospital hospital;
    private Department department;
    private User receptionist;
    private Staff doctor;
    private UserRoleHospitalAssignment doctorAssignment;
    private Patient aicha;

    @BeforeEach
    void setUp() {
        cleanUp();
        organization = organizationRepository.save(Organization.builder()
            .name("R16 Desk Network").code("R16D-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("R16 Desk Hospital").code("R16DH" + nextId()).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226553" + nextId()).email("r16d" + nextId() + "@hospital.test")
            .organization(organization).build());

        receptionist = userRepository.save(user("recep"));
        UserRoleHospitalAssignment desk = assignmentRepository.save(assignment(receptionist, role(ROLE_RECEPTIONIST)));
        department = departmentRepository.save(Department.builder()
            .name("R16 Pediatrie").code("R16P" + nextId()).email("r16ped" + nextId() + "@dept.test")
            .phoneNumber("+226-111-1616").active(true).hospital(hospital).assignment(desk).build());
        staffRepository.save(Staff.builder()
            .user(receptionist).hospital(hospital).assignment(desk).department(department)
            .jobTitle(JobTitle.values()[0]).employmentType(EmploymentType.FULL_TIME)
            .name("Desk " + receptionist.getFirstName()).active(true).build());

        User doctorUser = userRepository.save(user("doc"));
        doctorAssignment = assignmentRepository.save(assignment(doctorUser, role(ROLE_DOCTOR)));
        doctor = staffRepository.save(Staff.builder()
            .user(doctorUser).hospital(hospital).assignment(doctorAssignment).department(department)
            .jobTitle(JobTitle.values()[0]).employmentType(EmploymentType.FULL_TIME)
            .name("Dr " + doctorUser.getFirstName()).active(true).build());

        aicha = patient("Aïcha", "Kaboré", LocalDate.of(1991, 7, 22), "70161099");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        cleanUp();
    }

    private void cleanUp() {
        recallRepository.deleteAll();
        insuranceRepository.deleteAll();
        // The flow-board round trip writes an encounter (and its history).
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("DELETE FROM clinical.encounter_history");
        encounterRepository.deleteAll();
        appointmentRepository.deleteAll();
        // D926 corrects the address, which files an address-history row.
        applicationContext.getBean(com.example.hms.repository.PatientAddressHistoryRepository.class).deleteAll();
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    // ------------------------------------------------------------- appointments

    @Test
    @DisplayName("D923: a reschedule PUT stores the new date, time and notes, and a fresh read shows them")
    void aReschedulePersists() throws Exception {
        Appointment appt = appointment(aicha, LocalDate.now().plusDays(1), LocalTime.of(9, 0), AppointmentStatus.SCHEDULED);
        LocalDate newDate = LocalDate.now().plusDays(2);

        mockMvc.perform(put(API + "/appointments/{id}", appt.getId()).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(appointmentBody(aicha, newDate, "11:00", "11:30", "Demande du patient - demo-r16"))
                .with(asReceptionist()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.appointmentDate").value(newDate.toString()));

        Appointment stored = appointmentRepository.findById(appt.getId()).orElseThrow();
        assertThat(stored.getAppointmentDate()).isEqualTo(newDate);
        assertThat(stored.getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(stored.getEndTime()).isEqualTo(LocalTime.of(11, 30));
        assertThat(stored.getNotes()).isEqualTo("Demande du patient - demo-r16");
    }

    @Test
    @DisplayName("D937: a cancelled booking frees its slot for the next booking")
    void aCancelledBookingFreesTheSlot() throws Exception {
        LocalDate day = LocalDate.now().plusDays(12);
        Appointment first = appointment(aicha, day, LocalTime.of(14, 0), AppointmentStatus.SCHEDULED);
        mockMvc.perform(put(API + "/appointments/{id}/status", first.getId()).contextPath(API)
                .param("action", "cancel").with(asReceptionist()))
            .andExpect(status().isOk());

        Patient other = patient("Karim", "Sanogo", LocalDate.of(1985, 1, 3), "76112233");
        mockMvc.perform(post(API + "/appointments").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(appointmentBody(other, day, "14:00", "14:30", "Consultation"))
                .with(asReceptionist()))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("D946: a SCHEDULED appointment whose time has passed is marked absent without a fake confirmation")
    void aPastScheduledAppointmentCanBeMarkedAbsent() throws Exception {
        Appointment past = appointment(aicha, LocalDate.now().minusDays(1), LocalTime.of(8, 0), AppointmentStatus.SCHEDULED);

        mockMvc.perform(put(API + "/appointments/{id}/status", past.getId()).contextPath(API)
                .param("action", "no_show").with(asReceptionist()))
            .andExpect(status().isOk());

        assertThat(appointmentRepository.findById(past.getId()).orElseThrow().getStatus())
            .isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    @DisplayName("D985: booking the visit a recall asks for schedules the recall; cancelling it reopens the recall")
    void bookingARecallSchedulesIt() throws Exception {
        PatientRecall recall = recallRepository.save(PatientRecall.builder()
            .patient(aicha).hospital(hospital).department(department)
            .dueDate(LocalDate.now().plusDays(5)).reason("Contrôle").build());

        String body = mockMvc.perform(post(API + "/appointments").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(appointmentBody(aicha, LocalDate.now().plusDays(5), "10:00", "10:30", "Contrôle"))
                .with(asReceptionist()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));

        PatientRecall scheduled = recallRepository.findById(recall.getId()).orElseThrow();
        assertThat(scheduled.getStatus()).isEqualTo(RecallStatus.SCHEDULED);
        assertThat(scheduled.getLinkedAppointment().getId()).isEqualTo(appointmentId);

        mockMvc.perform(put(API + "/appointments/{id}/status", appointmentId).contextPath(API)
                .param("action", "cancel").with(asReceptionist()))
            .andExpect(status().isOk());
        PatientRecall reopened = recallRepository.findById(recall.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(RecallStatus.PENDING);
        assertThat(reopened.getLinkedAppointment()).isNull();
    }

    @Test
    @DisplayName("D985: a visit that does not answer the recall leaves it open; booking from the recall list links exactly that one")
    void onlyTheVisitARecallAsksForSchedulesIt() throws Exception {
        PatientRecall review = recallRepository.save(PatientRecall.builder()
            .patient(aicha).hospital(hospital)
            .dueDate(LocalDate.now().plusMonths(6)).reason("Bilan diabète").build());

        // An acute visit tomorrow: the recall names no department or clinician, so only an explicit link moves it.
        mockMvc.perform(post(API + "/appointments").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(appointmentBody(aicha, LocalDate.now().plusDays(1), "08:00", "08:30", "Fièvre"))
                .with(asReceptionist()))
            .andExpect(status().isCreated());
        assertThat(recallRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(RecallStatus.PENDING);

        String body = appointmentBody(aicha, LocalDate.now().plusMonths(6), "09:00", "09:30", "Bilan")
            .replace("\"reason\"", "\"recallId\":\"" + review.getId() + "\",\"reason\"");
        mockMvc.perform(post(API + "/appointments").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(asReceptionist()))
            .andExpect(status().isCreated());
        assertThat(recallRepository.findById(review.getId()).orElseThrow().getStatus())
            .isEqualTo(RecallStatus.SCHEDULED);
    }

    // ---------------------------------------------------------------- insurance

    @Test
    @DisplayName("D925: linking a new policy stores it, named after its payer when no name is typed, and as primary")
    void linkingANewPolicyWorks() throws Exception {
        for (boolean primary : List.of(false, true)) {
            String policy = "AMU-2026-00451" + (primary ? "2" : "1");
            mockMvc.perform(put(API + "/patient-insurances/link").contextPath(API)
                    .header("X-Hospital-Id", hospital.getId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"patientId":"%s","hospitalId":"%s","payerCode":"CNAMU","policyNumber":"%s","primary":%s}
                        """.formatted(aicha.getId(), hospital.getId(), policy, primary))
                    .with(asReceptionist()))
                .andExpect(status().isOk());
        }

        var stored = insuranceRepository.findAll();
        assertThat(stored).hasSize(2)
            .allSatisfy(i -> assertThat(i.getProviderName()).isEqualTo("CNAMU"));
        assertThat(stored).filteredOn(i -> i.isPrimary()).hasSize(1);
    }

    // ------------------------------------------------------------- demographics

    @Test
    @DisplayName("D926: the desk corrects name, DOB, phone, address and the guardian; a fresh read shows every field")
    void theDeskCorrectsDemographics() throws Exception {
        mockMvc.perform(patch(API + "/patients/{id}", aicha.getId()).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Aïchatou","lastName":"Kaboré","dateOfBirth":"1991-07-23","gender":"F",
                     "phoneNumberPrimary":"70 16 10 98","city":"Bobo-Dioulasso","addressLine1":"Secteur 22",
                     "emergencyContactName":"Mariam Ouattara","emergencyContactPhone":"70001014",
                     "emergencyContactRelationship":"Mère"}
                    """)
                .with(asReceptionist()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emergencyContactRelationship").value("Mère"));

        Patient stored = patientRepository.findById(aicha.getId()).orElseThrow();
        assertThat(stored.getFirstName()).isEqualTo("Aïchatou");
        assertThat(stored.getDateOfBirth()).isEqualTo(LocalDate.of(1991, 7, 23));
        assertThat(stored.getPhoneNumberPrimary()).isEqualTo("+22670161098");
        assertThat(stored.getCity()).isEqualTo("Bobo-Dioulasso");
        assertThat(stored.getEmergencyContactName()).isEqualTo("Mariam Ouattara");
        assertThat(stored.getEmergencyContactPhone()).isEqualTo("+22670001014");
        assertThat(stored.getEmergencyContactRelationship()).isEqualTo("Mère");
        // The correction is audited, naming the fields and never their values.
        var audit = applicationContext.getBean(com.example.hms.repository.AuditEventLogRepository.class).findAll()
            .stream()
            .filter(a -> a.getEventType() == com.example.hms.enums.AuditEventType.PATIENT_UPDATE
                && aicha.getId().toString().equals(a.getResourceId())
                && a.getEventDescription() != null && a.getEventDescription().startsWith("Patient demographics"))
            .toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getEventDescription()).contains("firstName").doesNotContain("Aïchatou");
    }

    @Test
    @DisplayName("D926: a required field sent blank is refused and nothing is changed")
    void blanksAreRefused() throws Exception {
        for (String body : List.of("{\"lastName\":\"  \"}", "{\"phoneNumberPrimary\":\" \"}",
                "{\"gender\":\"\"}")) {
            mockMvc.perform(patch(API + "/patients/{id}", aicha.getId()).contextPath(API)
                    .contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(asReceptionist()))
                .andExpect(status().isBadRequest());
        }

        Patient stored = patientRepository.findById(aicha.getId()).orElseThrow();
        assertThat(stored.getLastName()).isEqualTo("Kaboré");
        assertThat(stored.getPhoneNumberPrimary()).isEqualTo("70161099");
        assertThat(stored.getGender()).isEqualTo("F");
    }

    // ------------------------------------------------------------ match/search

    @Test
    @DisplayName("D924: the duplicate check finds the same name and birth date whatever phone is typed")
    void theDuplicateCheckIgnoresADifferentPhone() throws Exception {
        mockMvc.perform(get(API + "/reception/patients/duplicate-candidates").contextPath(API)
                .param("name", "Aicha Kabore").param("dob", "1991-07-22").param("phone", "+22670161000")
                .with(asReceptionist()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].patientId").value(aicha.getId().toString()));
    }

    @Test
    @DisplayName("D942: the registration match finds a number stored as typed, whatever format is typed now")
    void theRegistrationMatchFoldsPhoneFormats() throws Exception {
        for (String typed : List.of("+22670161099", "70 16 10 99", "0022670161099")) {
            mockMvc.perform(get(API + "/patients/registration-match").contextPath(API)
                    .param("phone", typed).with(asReceptionist()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].patientId", hasItem(aicha.getId().toString())));
        }
    }

    @Test
    @DisplayName("D943/D942: the patient list search ignores accents, and the picker search ignores phone formatting")
    void theSearchFoldsAccentsAndPhones() throws Exception {
        for (String typed : List.of("Kabore", "Aicha", "aïcha kaboré", "Kaboré Aïcha")) {
            mockMvc.perform(get(API + "/patients").contextPath(API)
                    .param("search", typed).with(asReceptionist()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(aicha.getId().toString())));
        }
        mockMvc.perform(get(API + "/patients/search").contextPath(API)
                .param("phone", "+226 70 16 10 99").with(asReceptionist()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].id", hasItem(aicha.getId().toString())));
    }

    // ------------------------------------------- the other write endpoints

    @Test
    @DisplayName("PUT /patients/{id} round trip: the replaced fields are stored, the phone in E.164")
    void aPatientPutPersists() throws Exception {
        mockMvc.perform(put(API + "/patients/{id}", aicha.getId()).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName":"Aïcha","lastName":"Kaboré","dateOfBirth":"1991-07-22","gender":"F",
                     "phoneNumberPrimary":"70 16 10 99","city":"Koudougou","country":"Burkina Faso",
                     "emergencyContactName":"Adjaratou Kaboré","emergencyContactRelationship":"Mère",
                     "userId":"%s","hospitalId":"%s"}
                    """.formatted(aicha.getUser().getId(), hospital.getId()))
                .with(asReceptionist()))
            .andExpect(status().isOk());

        Patient stored = patientRepository.findById(aicha.getId()).orElseThrow();
        assertThat(stored.getCity()).isEqualTo("Koudougou");
        assertThat(stored.getEmergencyContactRelationship()).isEqualTo("Mère");
        assertThat(stored.getPhoneNumberPrimary()).isEqualTo("+22670161099");
    }

    @Test
    @DisplayName("PUT /patient-insurances/{id} and /{id}/link round trips: the edit and the primary flag are stored")
    void insuranceEditsPersist() throws Exception {
        mockMvc.perform(put(API + "/patient-insurances/link").contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"patientId":"%s","hospitalId":"%s","payerCode":"CNAMU","policyNumber":"AMU-1"}
                    """.formatted(aicha.getId(), hospital.getId()))
                .with(asReceptionist()))
            .andExpect(status().isOk());
        UUID insuranceId = insuranceRepository.findAll().get(0).getId();

        mockMvc.perform(put(API + "/patient-insurances/{id}", insuranceId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"patientId":"%s","providerName":"Caisse Nationale AMU","policyNumber":"AMU-2"}
                    """.formatted(aicha.getId()))
                .with(asReceptionist()))
            .andExpect(status().isOk());
        mockMvc.perform(put(API + "/patient-insurances/{id}/link", insuranceId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"patientId":"%s","hospitalId":"%s","primary":true}
                    """.formatted(aicha.getId(), hospital.getId()))
                .with(asReceptionist()))
            .andExpect(status().isOk());

        var stored = insuranceRepository.findById(insuranceId).orElseThrow();
        assertThat(stored.getProviderName()).isEqualTo("Caisse Nationale AMU");
        assertThat(stored.getPolicyNumber()).isEqualTo("AMU-2");
    }

    @Test
    @DisplayName("PUT /assignments/{id} round trip: the hospital admin's edit is stored")
    void anAssignmentEditPersists() throws Exception {
        User admin = userRepository.save(user("hadm"));
        assignmentRepository.save(assignment(admin, role("ROLE_HOSPITAL_ADMIN")));
        User nurse = userRepository.save(user("nur"));
        UserRoleHospitalAssignment nurseRow = assignmentRepository.save(assignment(nurse, role("ROLE_NURSE")));
        LocalDate start = LocalDate.now().minusDays(3);

        mockMvc.perform(put(API + "/assignments/{id}", nurseRow.getId()).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"%s","hospitalId":"%s","roleId":"%s","startDate":"%s"}
                    """.formatted(nurse.getId(), hospital.getId(), nurseRow.getRole().getId(), start))
                .with(as(admin, "ROLE_HOSPITAL_ADMIN")))
            .andExpect(status().isOk());

        assertThat(assignmentRepository.findById(nurseRow.getId()).orElseThrow().getStartDate()).isEqualTo(start);
    }

    @Test
    @DisplayName("PATCH /reception/encounters/{id}/status round trip: the desk's move is stored")
    void aFlowBoardMovePersists() throws Exception {
        com.example.hms.model.Encounter visit = encounterRepository.save(com.example.hms.model.Encounter.builder()
            .patient(aicha).staff(doctor).hospital(hospital).assignment(doctorAssignment).department(department)
            .encounterType(com.example.hms.enums.EncounterType.CONSULTATION)
            .encounterDate(LocalDateTime.now()).status(com.example.hms.enums.EncounterStatus.ARRIVED)
            .code("R16ENC-" + nextId()).build());

        mockMvc.perform(patch(API + "/reception/encounters/{id}/status", visit.getId()).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"WAITING_FOR_PHYSICIAN\"}")
                .with(asReceptionist()))
            .andExpect(status().isNoContent());

        assertThat(encounterRepository.findById(visit.getId()).orElseThrow().getStatus())
            .isEqualTo(com.example.hms.enums.EncounterStatus.WAITING_FOR_PHYSICIAN);
    }

    // ------------------------------------------------------------------ helpers

    private String appointmentBody(Patient patient, LocalDate date, String start, String end, String notes) {
        return """
            {"patientId":"%s","appointmentDate":"%s","startTime":"%s","endTime":"%s","staffId":"%s",
             "hospitalId":"%s","departmentId":"%s","reason":"Consultation","notes":"%s"}
            """.formatted(patient.getId(), date, start, end, doctor.getId(), hospital.getId(),
            department.getId(), notes);
    }

    private Appointment appointment(Patient patient, LocalDate date, LocalTime start, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setPatient(patient);
        a.setStaff(doctor);
        a.setHospital(hospital);
        a.setDepartment(department);
        a.setAssignment(doctorAssignment);
        a.setAppointmentDate(date);
        a.setStartTime(start);
        a.setEndTime(start.plusMinutes(30));
        a.setStatus(status);
        a.setReason("Consultation");
        a.setNotes("demo-r16");
        a.setCreatedBy(receptionist);
        return appointmentRepository.save(a);
    }

    private Patient patient(String first, String last, LocalDate dob, String phone) {
        String suffix = nextId();
        Patient p = patientRepository.save(Patient.builder()
            .firstName(first).lastName(last).dateOfBirth(dob).gender("F")
            .address("Secteur 15").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary(phone).email("r16p" + suffix + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("71" + suffix)
            .organizationId(organization.getId()).hospitalId(hospital.getId())
            .user(userRepository.save(user("pat"))).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(p).hospital(hospital).mrn("CHW" + suffix)
            .registrationDate(LocalDate.now()).active(true).build());
        return p;
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code.substring(5)).code(code)
                .description(code).build()));
    }

    private UserRoleHospitalAssignment assignment(User user, Role role) {
        return UserRoleHospitalAssignment.builder()
            .assignmentCode("R16D-" + nextId()).description("r16").user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder().username("r16d" + prefix + suffix).passwordHash("hashed")
            .email("r16d" + prefix + suffix + "@example.test").firstName(prefix).lastName("R16" + suffix)
            .phoneNumber("+22678" + suffix).isActive(true).build();
    }

    private RequestPostProcessor asReceptionist() {
        return as(receptionist, ROLE_RECEPTIONIST);
    }

    private RequestPostProcessor as(User caller, String roleCode) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(roleCode));
        CustomUserDetails principal = new CustomUserDetails(caller.getId(), caller.getUsername(),
            caller.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, caller.getPasswordHash(),
            authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(caller.getId())
                .principalUsername(caller.getUsername())
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
        return String.format("%06d", sequence.incrementAndGet());
    }
}
