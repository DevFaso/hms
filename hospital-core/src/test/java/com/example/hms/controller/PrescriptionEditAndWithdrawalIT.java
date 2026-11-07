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
import com.example.hms.model.PatientProblem;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    @Autowired private com.example.hms.repository.PatientProblemRepository patientProblemRepository;
    private List<String> lastSummaries = List.of();
    @Autowired private com.example.hms.cdshooks.service.MedicationPrescribeRulesCdsService medicationPrescribeRules;

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

    /**
     * QA round 30 over HTTP (user decision 2026-11-02, and D1359): a new PRN
     * order without its minimum interval and with an unknown route is refused
     * with BOTH fields named in one answer; with them it is created and the
     * interval is stored as written.
     */
    @Test
    @DisplayName("QA round 30 / D1359: a PRN order names its interval, a route is a known route, in one answer")
    void aPrnOrderNamesItsIntervalAndTheRouteIsKnown() throws Exception {
        String refused = "{\"patientId\":\"" + patient.getId() + "\",\"staffId\":\"" + authorStaff.getId()
            + "\",\"encounterId\":\"" + encounter.getId() + "\",\"medicationName\":\"Paracétamol 500 mg\","
            + "\"dosage\":\"1 cp\",\"frequency\":\"si besoin\",\"route\":\"BOGUS\",\"quantity\":10}";
        mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(refused))
            .andExpect(status().isBadRequest())
            // QA D1378: not ticked, the as-needed wording is refused on the tick (the safety net).
            .andExpect(jsonPath("$.fieldErrors.asNeeded").exists())
            .andExpect(jsonPath("$.fieldErrors.route").exists());

        String ticked = refused.replace("\"quantity\":10", "\"quantity\":10,\"asNeeded\":true");
        mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ticked))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.prnMinIntervalHours").exists())
            .andExpect(jsonPath("$.fieldErrors.route").exists());

        String accepted = ticked.replace("\"route\":\"BOGUS\"", "\"route\":\"PO\",\"prnMinIntervalHours\":6");
        String answer = mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(accepted))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.prnMinIntervalHours").value(6))
            .andExpect(jsonPath("$.asNeeded").value(true))
            .andReturn().getResponse().getContentAsString();
        UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer, "$.id"));
        try {
            assertThat(prescriptionRepository.findById(created).orElseThrow().getPrnMinIntervalHours()).isEqualTo(6);
            assertThat(prescriptionRepository.findById(created).orElseThrow().isAsNeeded()).isTrue();
        } finally {
            auditEventLogRepository.deleteAllInBatch(auditEventLogRepository.findAll().stream()
                .filter(row -> created.toString().equals(row.getResourceId())).toList());
            prescriptionRepository.deleteById(created);
        }
    }

    /**
     * QA D1360 over HTTP (prn31.js): a controlled morphine order written "au
     * besoin", "à la demande", "si nécessaire" or "si douleur" without its
     * minimum interval was created (201). Each is now refused on the field.
     */
    @Test
    @DisplayName("QA D1360 / D1378: a morphine order in any as-needed wording is confirmed, then needs its interval")
    void d1360_morphinePrnInFrenchWordingsNeedsItsInterval() throws Exception {
        long before = prescriptionRepository.count();
        // QA D1378 (round 32): the round-31 wordings and the round-32 ones a word list had missed.
        for (String wording : new String[] {"au besoin", "à la demande", "si nécessaire", "si douleur", "SOS",
            "p.r.n.", "sur demande", "si EVA > 4", "si precisa", "s/p", "when necessary", "for pain"}) {
            String body = "{\"patientId\":\"" + patient.getId() + "\",\"staffId\":\"" + authorStaff.getId()
                + "\",\"encounterId\":\"" + encounter.getId() + "\",\"medicationName\":\"Morphine 2 mg\","
                + "\"dosage\":\"2 mg\",\"frequency\":\"" + wording + "\",\"route\":\"IV\",\"quantity\":10,"
                + "\"controlledSubstance\":true}";
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.asNeeded").exists());
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body.replace("\"controlledSubstance\":true", "\"controlledSubstance\":true,\"asNeeded\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.prnMinIntervalHours").exists());
        }
        assertThat(prescriptionRepository.count()).isEqualTo(before);
    }

    /**
     * QA round 30: an older PRN order, written before the interval existed,
     * is still withdrawn by its author without one (no new requirement on a
     * withdrawal), while an edit of its content names the interval.
     */
    @Test
    @DisplayName("QA round 30: an older PRN order is withdrawn without an interval; an edit of its content needs one")
    void anOlderPrnOrderIsWithdrawnWithoutAnIntervalButAnEditNeedsOne() throws Exception {
        prescription.setFrequency("PRN");
        // V260 ticked the older orders the round-31 list read ("PRN").
        prescription.setAsNeeded(true);
        prescription = prescriptionRepository.save(prescription);

        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null).replace("3x/day", "PRN")
                    .replace("\"dosage\":\"500 mg\"", "\"dosage\":\"1 g\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.prnMinIntervalHours").exists());

        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription = prescriptionRepository.save(prescription);
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", "DISCONTINUED", "Traitement terminé").replace("3x/day", "PRN")))
            .andExpect(status().isOk());
        assertThat(prescriptionRepository.findById(prescription.getId()).orElseThrow().getStatus())
            .isEqualTo(PrescriptionStatus.DISCONTINUED);
    }

    /**
     * QA D1391 / D1395 (round 33) and QA D1419 / D1434 (round 34, changed
     * assertions: a limit and a wording that states no schedule were refused on
     * the tick), over the real endpoint: a condition is refused on the tick;
     * anything the reader cannot place is asked about once on frequencyConfirmed,
     * controlled drug included, and the same order with the answer is created,
     * never as-needed; a schedule is created at once.
     */
    @Test
    @DisplayName("QA D1391 / D1395: an unticked order states a fixed schedule, in FR, ES and EN")
    void d1391_d1395_anUntickedOrderStatesAFixedSchedule() throws Exception {
        long before = prescriptionRepository.count();
        for (String wording : new String[] {"pour douleur", "toutes les 6 h pour douleur", "q6h si fièvre",
            "para el dolor", "in case of pain", "douleur", "pain", "ad lib", "SOS", "for pain"}) {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(wording)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.asNeeded").exists())
                .andExpect(jsonPath("$.fieldErrors.frequencyConfirmed").doesNotExist());
            // Confirming does not answer a condition.
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(wording).replace("\"quantity\":3", "\"quantity\":3,\"frequencyConfirmed\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.asNeeded").exists());
        }
        String[] asked = {"max 4/j", "jusqu'à 4 fois par jour", "1 à 3 fois par jour", "up to 4 times a day",
            "selon prescription médicale", "SP + AQ", "selon ordonnance", "CPS", "protocole national", "5 jours", "x3"};
        for (String wording : asked) {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(wording)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.frequencyConfirmed").exists())
                .andExpect(jsonPath("$.fieldErrors.asNeeded").doesNotExist());
        }
        assertThat(prescriptionRepository.count()).isEqualTo(before);

        for (String wording : asked) {
            createdOnce(createBody(wording).replace("\"quantity\":3",
                "\"quantity\":3,\"controlledSubstance\":true,\"frequencyConfirmed\":true"));
        }

        for (String wording : new String[] {"Prise unique (TPI-SP 2)", "TPI-SP", "SP dose unique",
            "SP-AQ 1 fois par jour", "SMC : SP + AQ, 1 fois par mois", "4 cp 2 fois par jour (poids > 35 kg)",
            "3 fois par jour selon ordonnance", "2 fois par jour selon protocole", "Q6H", "cada 6 horas",
            "3 times a day", "prendre 1 comprimé 3 fois par jour", "take 1 tablet twice daily", "H0 H8 H24 H36 H48 H60",
            "J1 J2 J3", "au coucher", "chaque semaine", "perfusion continue", "bolus"}) {
            createdOnce(createBody(wording));
        }
    }

    /**
     * Adult paracetamol over the real endpoint: the alerts come back on a created order (201,
     * never a refusal), from the real engine, facts reader and transaction.
     */
    @Test
    void adultParacetamolAlertsComeBackOnACreatedOrder() throws Exception {
        assertThat(paracetamolCards("2 000 mg", "3 fois par jour", ""))
            .containsExactlyInAnyOrder("warning", "warning");
        assertThat(paracetamolCards("1 g", "3 fois par jour", "")).containsExactly("info");
        assertThat(paracetamolCards("500 mg", "4 fois par jour", "")).isEmpty();
        assertThat(paracetamolCards("1 g", "4 fois par jour", "")).containsExactly("info");
        assertThat(paracetamolCards("1 g", "si besoin", ",\"asNeeded\":true,\"prnMinIntervalHours\":1"))
            .containsExactlyInAnyOrder("warning", "warning");
    }

    /** All active orders and the problem list, through real rows: the second order tips the total. */
    @Test
    void adultParacetamolCountsTheOtherOrdersAndTheCodedProblems() throws Exception {
        Prescription other = prescriptionRepository.save(Prescription.builder()
            .patient(patient).staff(authorStaff).encounter(encounter).hospital(hospital)
            .assignment(authorStaff.getAssignment())
            .medicationName("Paracétamol 500 mg").medicationDisplayName("Paracétamol 500 mg")
            .dosage("1 g").frequency("2 fois par jour").quantity(BigDecimal.TEN).quantityUnit("cp")
            .status(PrescriptionStatus.SIGNED).build());
        PatientProblem gilbert = patientProblemRepository.save(PatientProblem.builder()
            .patient(patient).hospital(hospital).recordedBy(authorStaff)
            .problemCode("E80.4").problemDisplay("Gilbert").build());
        try {
            // 2 g already: the ceiling is 2,000 mg with the coded Gilbert syndrome, and this is over it.
            paracetamolCards("500 mg", "1 fois par jour", "");
            assertThat(lastSummaries).anyMatch(s -> s.contains("2500 mg") && s.contains("2000 mg"));
            patientProblemRepository.deleteById(gilbert.getId());
            gilbert = null;
            // No code now: 2.5 g in all is under the ceiling, and the groups are still named: information.
            assertThat(paracetamolCards("500 mg", "1 fois par jour", "")).contains("info").doesNotContain("critical");
            assertThat(lastSummaries).noneMatch(s -> s.contains("2500 mg") && s.contains("4000 mg"));
            // Each order alone is fine; 5 g together is over the general ceiling.
            paracetamolCards("1 g", "3 fois par jour", "");
            assertThat(lastSummaries).anyMatch(s -> s.contains("5000 mg") && s.contains("4000 mg"));
        } finally {
            if (gilbert != null) {
                patientProblemRepository.deleteById(gilbert.getId());
            }
            prescriptionRepository.deleteById(other.getId());
        }
    }

    /**
     * A CDS hook runs with no session (open-in-view is off), and the check of the other orders
     * reads each one's LAZY encounter: its own transaction carries it (QA D1121). The test
     * profile rethrows a rule's exception.
     */
    @Test
    void adultParacetamolReadsTheOtherOrdersFromAHookWithNoSession() {
        Prescription other = prescriptionRepository.save(Prescription.builder()
            .patient(patient).staff(authorStaff).encounter(encounter).hospital(hospital)
            .assignment(authorStaff.getAssignment())
            .medicationName("Paracétamol 500 mg").medicationDisplayName("Paracétamol 500 mg")
            .dosage("1 g").frequency("4 fois par jour").quantity(BigDecimal.TEN).quantityUnit("cp")
            .status(PrescriptionStatus.SIGNED).build());
        try {
            java.util.Map<String, Object> draft = java.util.Map.of(
                "resourceType", "MedicationRequest",
                "medicationCodeableConcept", java.util.Map.of("text", "Paracétamol"),
                "dosageInstruction", List.of(java.util.Map.of("text", "1 g")));
            java.util.Map<String, Object> context = java.util.Map.of(
                "patientId", patient.getId().toString(),
                "medications", java.util.Map.of("entry", List.of(java.util.Map.of("resource", draft))));
            var response = medicationPrescribeRules.evaluate(new com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest(
                "medication-prescribe", "i", null, null, "Practitioner/x", context, null));
            assertThat(response.cards()).extracting(com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard::summary)
                .anyMatch(summary -> summary.contains("5000 mg") && summary.contains("4000 mg"));
        } finally {
            prescriptionRepository.deleteById(other.getId());
        }
    }

    /** The contraindication goes through the existing override: refused, then saved with a recorded reason. */
    @Test
    void severeHepaticInsufficiencySavesOnlyWithARecordedReason() throws Exception {
        PatientProblem severe = patientProblemRepository.save(PatientProblem.builder()
            .patient(patient).hospital(hospital).recordedBy(authorStaff)
            .problemCode("K72.9").problemDisplay("Hepatic failure")
            .severity(com.example.hms.enums.ProblemSeverity.SEVERE).build());
        String refused = "{\"patientId\":\"" + patient.getId() + "\",\"staffId\":\"" + authorStaff.getId()
            + "\",\"encounterId\":\"" + encounter.getId() + "\",\"medicationName\":\"Paracétamol\","
            + "\"dosage\":\"500 mg\",\"frequency\":\"3 fois par jour\",\"route\":\"PO\",\"quantity\":3}";
        try {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions").contextPath(API_CONTEXT).with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON).content(refused))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.cdsAdvisories[0].indicator").value("critical"));
            String noReason = refused.replace("\"quantity\":3", "\"quantity\":3,\"forceOverride\":true");
            mockMvc.perform(post(API_CONTEXT + "/prescriptions").contextPath(API_CONTEXT).with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON).content(noReason))
                .andExpect(status().isBadRequest());
            String reasoned = refused.replace("\"quantity\":3",
                "\"quantity\":3,\"forceOverride\":true,\"overrideReason\":\"Décision du spécialiste\"");
            String answer = mockMvc.perform(post(API_CONTEXT + "/prescriptions").contextPath(API_CONTEXT).with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON).content(reasoned))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
            UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer, "$.id"));
            assertThat(prescriptionRepository.findById(created).orElseThrow().getOverrideReason())
                .isEqualTo("Décision du spécialiste");
            auditEventLogRepository.deleteAllInBatch(auditEventLogRepository.findAll().stream()
                .filter(row -> created.toString().equals(row.getResourceId())).toList());
            prescriptionRepository.deleteById(created);
        } finally {
            patientProblemRepository.deleteById(severe.getId());
        }
    }

    private List<String> paracetamolCards(String dose, String frequency, String more) throws Exception {
        String body = "{\"patientId\":\"" + patient.getId() + "\",\"staffId\":\"" + authorStaff.getId()
            + "\",\"encounterId\":\"" + encounter.getId() + "\",\"medicationName\":\"Paracétamol\","
            + "\"dosage\":\"" + dose + "\",\"frequency\":\"" + frequency + "\",\"route\":\"PO\",\"quantity\":3" + more + "}";
        String answer = mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer, "$.id"));
        auditEventLogRepository.deleteAllInBatch(auditEventLogRepository.findAll().stream()
            .filter(row -> created.toString().equals(row.getResourceId())).toList());
        prescriptionRepository.deleteById(created);
        lastSummaries = com.jayway.jsonpath.JsonPath.read(answer, "$.cdsAdvisories[*].summary");
        List<String> indicators = com.jayway.jsonpath.JsonPath.read(answer, "$.cdsAdvisories[*].indicator");
        return indicators;
    }

    private void createdOnce(String body) throws Exception {
        String answer = mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.asNeeded").value(false))
            .andReturn().getResponse().getContentAsString();
        UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer, "$.id"));
        auditEventLogRepository.deleteAllInBatch(auditEventLogRepository.findAll().stream()
            .filter(row -> created.toString().equals(row.getResourceId())).toList());
        prescriptionRepository.deleteById(created);
    }

    /** QA D1433, over the real endpoint: a fraction of an hour is refused on its field, not cut down; a whole one stands. */
    @Test
    @DisplayName("QA D1433: a non-integer PRN interval is refused on its field")
    void d1433_aFractionOfAnHourIsRefusedOnItsField() throws Exception {
        long before = prescriptionRepository.count();
        for (String hours : new String[] {"1.5", "2.9", "6.5", "0.5"}) {
            mockMvc.perform(post(API_CONTEXT + "/prescriptions")
                    .contextPath(API_CONTEXT)
                    .with(as(authorUser))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("si besoin").replace("\"quantity\":3",
                        "\"quantity\":3,\"asNeeded\":true,\"prnMinIntervalHours\":" + hours)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.prnMinIntervalHours").exists());
        }
        assertThat(prescriptionRepository.count()).isEqualTo(before);
    }

    private String createBody(String frequency) {
        return "{\"patientId\":\"" + patient.getId() + "\",\"staffId\":\"" + authorStaff.getId()
            + "\",\"encounterId\":\"" + encounter.getId() + "\",\"medicationName\":\"Fansidar\","
            + "\"dosage\":\"3 cp\",\"frequency\":\"" + frequency + "\",\"route\":\"PO\",\"quantity\":3}";
    }

    /**
     * QA D1391 / D1395 (round 33), replacing the round-32 assertion: an edit is
     * judged on what it WROTE. An older order ("SOS", left unticked by V260)
     * whose frequency the edit leaves alone stays editable (it was refused,
     * which made such an order impossible to correct at all); an edit that
     * writes a new frequency is held to the rule; its withdrawal is never
     * refused for it.
     */
    @Test
    @DisplayName("QA D1391 / D1395: an older unticked order is edited freely until the edit writes a frequency")
    void d1391_anOlderUntickedOrderIsEditedUntilTheEditWritesAFrequency() throws Exception {
        prescription.setFrequency("SOS");
        prescription = prescriptionRepository.save(prescription);

        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null).replace("3x/day", "SOS")
                    .replace("\"dosage\":\"500 mg\"", "\"dosage\":\"1 g\"")))
            .andExpect(status().isOk());
        Prescription edited = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        assertThat(edited.getDosage()).isEqualTo("1 g");
        assertThat(edited.getFrequency()).as("the old text is kept as written").isEqualTo("SOS");
        assertThat(edited.isAsNeeded()).isFalse();

        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null).replace("3x/day", "pour douleur")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.asNeeded").exists());
        // QA D1419 (changed: this was refused on the tick): an edit that writes a wording not read is asked
        // once on its own field, and the answer saves it; the text is kept as written, not as-needed.
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null).replace("3x/day", "max 4/j")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.frequencyConfirmed").exists());
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null)
                    .replace("\"3x/day\"", "\"max 4/j\",\"frequencyConfirmed\":true")))
            .andExpect(status().isOk());
        assertThat(prescriptionRepository.findById(prescription.getId()).orElseThrow().isAsNeeded()).isFalse();
        // An edit that leaves that wording as it is needs no answer.
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", null, null).replace("3x/day", "max 4/j")))
            .andExpect(status().isOk());

        prescription = prescriptionRepository.findById(prescription.getId()).orElseThrow();
        prescription.setStatus(PrescriptionStatus.SIGNED);
        prescription = prescriptionRepository.save(prescription);
        mockMvc.perform(put(API_CONTEXT + UPDATE_PATH, prescription.getId())
                .contextPath(API_CONTEXT)
                .with(as(authorUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Amoxicilline 500 mg", "DISCONTINUED", "Traitement terminé").replace("3x/day", "max 4/j")))
            .andExpect(status().isOk());
        assertThat(prescriptionRepository.findById(prescription.getId()).orElseThrow().getStatus())
            .isEqualTo(PrescriptionStatus.DISCONTINUED);
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
