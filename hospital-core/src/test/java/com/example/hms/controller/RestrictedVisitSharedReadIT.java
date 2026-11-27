package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProcedureUrgency;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D4261: content tied to a restricted visit follows the visit's
 * restriction in every shared view.
 *
 * <p>A patient has two visits at hospital A: an ordinary one, and one the
 * hospital tagged with a sensitive category. Each produced a prescription, a
 * lab result, an imaging order, a procedure order and a vital sign. The
 * patient is then linked at hospital B and is in care there. A doctor of B
 * reads the shared record through every view: what the ordinary visit
 * produced is there, what the restricted visit produced is in none of them.
 * A doctor of A still reads all of it.
 */
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("D4261: content tied to a restricted visit follows the visit's restriction in every shared view")
class RestrictedVisitSharedReadIT extends BaseIT {

    private static final String API = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";

    private static final String PLAIN_MEDICINE = "Amoxicilline R81";
    private static final String RESTRICTED_MEDICINE = "Lamivudine R81";
    private static final String PLAIN_RESULT = "R81-VALEUR-ORDINAIRE";
    private static final String RESTRICTED_RESULT = "R81-VALEUR-RESTREINTE";
    private static final String PLAIN_STUDY = "Radio R81 ordinaire";
    private static final String RESTRICTED_STUDY = "Echo R81 restreinte";
    private static final String PLAIN_PROCEDURE = "Suture R81 ordinaire";
    private static final String RESTRICTED_PROCEDURE = "Geste R81 restreint";
    private static final int PLAIN_HEART_RATE = 61;
    private static final int RESTRICTED_HEART_RATE = 187;

    private final AtomicInteger sequence = new AtomicInteger();

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
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabResultRepository labResultRepository;
    @Autowired private ImagingOrderRepository imagingOrderRepository;
    @Autowired private ProcedureOrderRepository procedureOrderRepository;
    @Autowired private PatientVitalSignRepository vitalSignRepository;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private Actor doctorA;
    private Actor doctorB;
    private Patient patient;
    private Prescription plainPrescription;
    private Prescription restrictedPrescription;
    private UUID restrictedImagingOrderId;
    private final List<UUID> encounterIds = new ArrayList<>();
    private final List<UUID> prescriptionIds = new ArrayList<>();
    private final List<UUID> imagingOrderIds = new ArrayList<>();
    private final List<UUID> procedureOrderIds = new ArrayList<>();

    private record Actor(User user, Staff staff, UserRoleHospitalAssignment assignment, Hospital hospital) { }

    @BeforeEach
    void setUp() {
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
        organization = organizationRepository.save(Organization.builder()
            .name("Réseau R81")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospitalA = saveHospital("Hôpital A R81");
        hospitalB = saveHospital("Hôpital B R81");
        doctorA = doctor("medecinA", hospitalA);
        doctorB = doctor("medecinB", hospitalB);
        patient = createPatient();
        register(hospitalA);

        LabTestDefinition test = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("R81-" + nextId())
            .name("Analyse R81")
            .approvalStatus(com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE)
            .build());

        Encounter plainVisit = visitAtA(LocalDateTime.now().minusDays(20), null);
        Encounter restrictedVisit = visitAtA(LocalDateTime.now().minusDays(10), SensitivityCategory.HIV);
        plainPrescription = produce(plainVisit, test, PLAIN_MEDICINE, PLAIN_RESULT, PLAIN_STUDY, PLAIN_PROCEDURE,
            PLAIN_HEART_RATE);
        restrictedPrescription = produce(restrictedVisit, test, RESTRICTED_MEDICINE, RESTRICTED_RESULT,
            RESTRICTED_STUDY, RESTRICTED_PROCEDURE, RESTRICTED_HEART_RATE);
        restrictedImagingOrderId = imagingOrderIds.get(imagingOrderIds.size() - 1);

        // The designed flow: the desk of B links the patient, who is then seen there.
        register(hospitalB);
        encounterIds.add(encounterRepository.save(Encounter.builder()
            .patient(patient).staff(doctorB.staff()).hospital(hospitalB).assignment(doctorB.assignment())
            .encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now().minusHours(1))
            .status(EncounterStatus.IN_PROGRESS).code("ENC-R81-" + nextId()).build()).getId());
        HospitalContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // What this class made and the shared wipe does not know, children first.
        prescriptionRepository.deleteAllByIdInBatch(prescriptionIds);
        imagingOrderRepository.deleteAllByIdInBatch(imagingOrderIds);
        procedureOrderRepository.deleteAllByIdInBatch(procedureOrderIds);
        applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update("DELETE FROM lab.lab_results");
        labOrderRepository.deleteAll();
        encounterRepository.deleteAllByIdInBatch(encounterIds);
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    @Test
    @DisplayName("at hospital B, every shared view shows what the ordinary visit produced and nothing of the restricted visit")
    void theOtherHospitalReadsNothingOfTheRestrictedVisit() throws Exception {
        UUID id = patient.getId();
        UUID b = hospitalB.getId();

        // The medication views.
        String medications = readAtB("/patients/{id}/medications", id);
        assertShared(medications, "medications", PLAIN_MEDICINE, RESTRICTED_MEDICINE);
        String list = bodyOf(mockMvc.perform(get(API + "/prescriptions").param("patientId", id.toString())
            .with(acting(doctorB))).andExpect(status().isOk()).andReturn());
        assertShared(list, "prescription list", PLAIN_MEDICINE, RESTRICTED_MEDICINE);
        // The count of the list does not include the withheld prescription either.
        assertThat(list).as("prescription list count").contains("\"totalElements\":1");
        String timeline = bodyOf(mockMvc.perform(get(API + "/medication-history/patient/{id}/timeline", id)
            .param("hospitalId", b.toString()).with(acting(doctorB))).andExpect(status().isOk()).andReturn());
        assertShared(timeline, "medication timeline", PLAIN_MEDICINE, RESTRICTED_MEDICINE);

        // By id: the ordinary prescription opens, the restricted one answers as one that does not exist.
        mockMvc.perform(get(API + "/prescriptions/{id}", plainPrescription.getId()).with(acting(doctorB)))
            .andExpect(status().isOk());
        mockMvc.perform(get(API + "/prescriptions/{id}", restrictedPrescription.getId()).with(acting(doctorB)))
            .andExpect(status().isNotFound());

        // The chart views.
        String chart = readAtB("/patients/{id}/chart-review", id);
        assertShared(chart, "chart review medications", PLAIN_MEDICINE, RESTRICTED_MEDICINE);
        assertShared(chart, "chart review results", PLAIN_RESULT, RESTRICTED_RESULT);
        assertShared(chart, "chart review imaging", PLAIN_STUDY, RESTRICTED_STUDY);
        assertShared(chart, "chart review procedures", PLAIN_PROCEDURE, RESTRICTED_PROCEDURE);
        String snapshot = readAtB("/me/patients/{id}/snapshot", id);
        assertShared(snapshot, "snapshot medications", PLAIN_MEDICINE, RESTRICTED_MEDICINE);
        assertThat(snapshot).as("snapshot labs").doesNotContain(RESTRICTED_RESULT);

        // The export.
        String export = readAtB("/patients/{id}/fhir-record", id);
        assertShared(export, "FHIR record medications", PLAIN_MEDICINE, RESTRICTED_MEDICINE);
        assertThat(export).as("FHIR record observations").doesNotContain(RESTRICTED_RESULT);

        // The order and result lists.
        String results = readAtB("/patients/{id}/lab-results", id);
        assertShared(results, "lab results", PLAIN_RESULT, RESTRICTED_RESULT);
        String imaging = readAtB("/imaging/orders/patient/{id}", id);
        assertShared(imaging, "imaging orders", PLAIN_STUDY, RESTRICTED_STUDY);
        mockMvc.perform(get(API + "/imaging/orders/{id}", restrictedImagingOrderId).with(acting(doctorB)))
            .andExpect(status().isNotFound());
        String procedures = readAtB("/procedure-orders/patient/{id}", id);
        assertShared(procedures, "procedure orders", PLAIN_PROCEDURE, RESTRICTED_PROCEDURE);

        // A vital sign names no visit: it follows the day and the hospital of the restricted visit.
        String vitals = readAtB("/patients/{id}/vitals/recent", id);
        assertShared(vitals, "recent vitals", "\"heartRateBpm\":" + PLAIN_HEART_RATE,
            "\"heartRateBpm\":" + RESTRICTED_HEART_RATE);
    }

    @Test
    @DisplayName("at hospital A, where the visit was recorded, nothing is withheld")
    void theRecordingHospitalStillReadsItsOwnVisit() throws Exception {
        UUID id = patient.getId();
        String medications = bodyOf(mockMvc.perform(get(API + "/patients/{id}/medications", id)
            .with(acting(doctorA))).andExpect(status().isOk()).andReturn());
        assertThat(medications).contains(PLAIN_MEDICINE).contains(RESTRICTED_MEDICINE);
        mockMvc.perform(get(API + "/prescriptions/{id}", restrictedPrescription.getId()).with(acting(doctorA)))
            .andExpect(status().isOk());
        String chart = bodyOf(mockMvc.perform(get(API + "/patients/{id}/chart-review", id)
            .with(acting(doctorA))).andExpect(status().isOk()).andReturn());
        assertThat(chart).contains(RESTRICTED_MEDICINE).contains(RESTRICTED_RESULT)
            .contains(RESTRICTED_STUDY).contains(RESTRICTED_PROCEDURE);
    }

    /** The view really reads across hospitals (the ordinary row is there), and the restricted row is not. */
    private static void assertShared(String body, String view, String shown, String withheld) {
        assertThat(body).as(view + ": what the ordinary visit produced is shared").contains(shown);
        assertThat(body).as(view + ": what the restricted visit produced is withheld").doesNotContain(withheld);
    }

    private String readAtB(String path, UUID patientId) throws Exception {
        return bodyOf(mockMvc.perform(get(API + path, patientId).with(acting(doctorB)))
            .andExpect(status().isOk()).andReturn());
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Encounter visitAtA(LocalDateTime when, SensitivityCategory category) {
        Encounter visit = encounterRepository.save(Encounter.builder()
            .patient(patient).staff(doctorA.staff()).hospital(hospitalA).assignment(doctorA.assignment())
            .encounterType(EncounterType.CONSULTATION).encounterDate(when).checkoutTimestamp(when.plusHours(1))
            .status(EncounterStatus.COMPLETED).sensitivityCategory(category)
            .code("ENC-R81-" + nextId()).build());
        encounterIds.add(visit.getId());
        return visit;
    }

    /** Everything one visit at A produces: a prescription, a released result, an imaging order, a procedure order, a vital sign. */
    private Prescription produce(Encounter visit, LabTestDefinition test, String medicine, String resultValue,
                                 String study, String procedure, int heartRate) {
        LocalDateTime when = visit.getEncounterDate();
        Prescription prescription = prescriptionRepository.save(Prescription.builder()
            .patient(patient).staff(doctorA.staff()).encounter(visit).hospital(hospitalA)
            .assignment(doctorA.assignment()).medicationName(medicine).quantity(BigDecimal.TEN)
            .status(PrescriptionStatus.SIGNED).build());
        prescriptionIds.add(prescription.getId());
        LabOrder order = labOrderRepository.save(LabOrder.builder()
            .patient(patient).orderingStaff(doctorA.staff()).labTestDefinition(test).hospital(hospitalA)
            .assignment(doctorA.assignment()).encounter(visit).orderDatetime(when)
            .clinicalIndication("Suivi").status(LabOrderStatus.RESULTED).build());
        labResultRepository.save(LabResult.builder()
            .labOrder(order).assignment(doctorA.assignment()).resultValue(resultValue).resultDate(when.plusMinutes(30))
            .released(true).releasedAt(when.plusMinutes(40)).build());
        imagingOrderIds.add(imagingOrderRepository.save(ImagingOrder.builder()
            .patient(patient).hospital(hospitalA).modality(ImagingModality.XRAY).studyType(study)
            .status(com.example.hms.enums.ImagingOrderStatus.ORDERED).orderedAt(when)
            .encounterId(visit.getId()).build()).getId());
        procedureOrderIds.add(procedureOrderRepository.save(ProcedureOrder.builder()
            .patient(patient).hospital(hospitalA).orderingProvider(doctorA.staff()).encounter(visit)
            .procedureName(procedure).indication("Indication").urgency(ProcedureUrgency.ROUTINE)
            .orderedAt(when).build()).getId());
        vitalSignRepository.save(PatientVitalSign.builder()
            .patient(patient).hospital(hospitalA).recordedAt(when.plusMinutes(5)).heartRateBpm(heartRate).build());
        return prescription;
    }

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name)
            .code("H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 rue principale")
            .phoneNumber("+226555" + id)
            .email("contact" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Actor doctor(String prefix, Hospital hospital) {
        Role role = roleRepository.findByCode(ROLE_DOCTOR)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("DOCTOR").code(ROLE_DOCTOR).description(ROLE_DOCTOR).build()));
        User user = userRepository.save(buildUser(prefix));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(ROLE_DOCTOR + " at " + hospital.getName())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        Staff staff = staffRepository.save(Staff.builder()
            .user(user)
            .hospital(hospital)
            .assignment(assignment)
            .jobTitle(JobTitle.DOCTOR)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name(prefix)
            .active(true)
            .build());
        return new Actor(user, staff, assignment, hospital);
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@example.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22677" + suffix)
            .isActive(true)
            .build();
    }

    private Patient createPatient() {
        String suffix = nextId();
        User user = userRepository.save(buildUser("patient"));
        return patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Fictive")
            .dateOfBirth(LocalDate.of(1990, 5, 12))
            .gender("F")
            .address("Adresse")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("awa" + suffix + "@patient.test")
            .emergencyContactName("Issa Fictif")
            .emergencyContactPhone("+22679" + suffix)
            .organizationId(organization.getId())
            .hospitalId(hospitalA.getId())
            .user(user)
            .build());
    }

    private void register(Hospital hospital) {
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN-" + nextId())
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
    }

    private RequestPostProcessor acting(Actor actor) {
        return request -> {
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
            CustomUserDetails principal = new CustomUserDetails(actor.user().getId(), actor.user().getUsername(),
                actor.user().getPasswordHash(), true, authorities);
            Authentication auth = new UsernamePasswordAuthenticationToken(principal,
                actor.user().getPasswordHash(), authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(actor.user().getId())
                .principalUsername(actor.user().getUsername())
                .activeOrganizationId(organization.getId())
                .activeHospitalId(actor.hospital().getId())
                .permittedOrganizationIds(Set.of(organization.getId()))
                .permittedHospitalIds(Set.of(actor.hospital().getId()))
                .build());
            request.setContextPath(API);
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
