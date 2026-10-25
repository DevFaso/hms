package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Notification;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.service.scheduled.ObstetricAlertEscalationScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D605 (High, patient safety): the exact round-9 scenario through the REAL
 * filter chain, services and sweep (with its ShedLock) on H2. A midwife
 * charts FHR 95 and BP 165/112; before the fix only she was notified, and
 * DOCTOR_A and NURSE_A had nothing. Now the hospital's doctors hear at once
 * and get an in-basket item, the sweep widens to the nurses while nobody
 * acknowledges, and stops once a doctor does.
 */
@AutoConfigureMockMvc
class ObstetricAlertEscalationIT extends BaseIT {

    private static final String API = "/api";

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private InBasketItemRepository inBasketItemRepository;
    @Autowired private ObstetricAlertEscalationRepository escalationRepository;
    @Autowired private ObstetricAlertEscalationScheduler scheduler;
    @Autowired private OnCallScheduleRepository onCallScheduleRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Organization organization;
    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private Actor midwife;
    private Actor doctor;
    private Actor nurse;
    private Actor foreignDoctor;

    private record Actor(User user, UserRoleHospitalAssignment assignment, String role) {
    }

    @BeforeEach
    void seed() {
        wipe();
        organization = organizationRepository.save(Organization.builder()
            .name("Keneya Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = saveHospital("Maternity Hospital");
        otherHospital = saveHospital("Somewhere Else");
        midwife = staffActor("midwife", "ROLE_MIDWIFE", JobTitle.MIDWIFE, hospital);
        doctor = staffActor("doctor", "ROLE_DOCTOR", JobTitle.DOCTOR, hospital);
        nurse = staffActor("nurse", "ROLE_NURSE", JobTitle.NURSE, hospital);
        foreignDoctor = staffActor("foreign", "ROLE_DOCTOR", JobTitle.DOCTOR, otherHospital);
        patient = createPatient();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        wipe();
    }

    @Test
    @DisplayName("QA D605: FHR 95 + BP 165/112 reaches the doctors at once, escalates to nurses, stops on acknowledgement")
    void urgentPartographAlertReachesTheTeamAndEscalatesUntilAcknowledged() throws Exception {
        UUID episodeId = startEpisode();

        PartographEntryRequestDTO entry = new PartographEntryRequestDTO();
        entry.setObservationTime(LocalDateTime.now().withNano(0));
        entry.setFetalHeartRateBpm(95);
        entry.setSystolicBpMmHg(165);
        entry.setDiastolicBpMmHg(112);
        mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/entries", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(entry))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());

        // First notice: the documenter as before, AND the doctor (no on-call rota here).
        List<ObstetricAlertEscalation> rows = escalationRepository.findAll();
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r.getHospitalId()).isEqualTo(hospital.getId());
            assertThat(r.getEscalationLevel()).isZero();
            assertThat(r.getDocumentedByUserId()).isEqualTo(midwife.user().getId());
        });
        assertThat(notificationsOf(doctor)).filteredOn(n -> ObstetricAlertEscalationWorker.NOTIFICATION_TYPE.equals(n.getType()))
            .hasSize(rows.size());
        assertThat(notificationsOf(midwife)).isNotEmpty();
        assertThat(notificationsOf(nurse)).isEmpty();
        assertThat(notificationsOf(foreignDoctor)).as("never another hospital's staff").isEmpty();
        List<InBasketItem> doctorItems = inBasketOf(doctor);
        assertThat(doctorItems).hasSize(rows.size());
        assertThat(inBasketOf(midwife)).as("the documenter cannot acknowledge her own alert").isEmpty();

        // Nobody acknowledges: the real sweep widens the circle to the nurses.
        backdate(rows);
        sweep();
        assertThat(escalationRepository.findAll()).allSatisfy(r -> assertThat(r.getEscalationLevel()).isEqualTo((short) 1));
        assertThat(notificationsOf(nurse))
            .filteredOn(n -> ObstetricAlertEscalationWorker.ESCALATION_TYPE.equals(n.getType()))
            .hasSize(rows.size())
            .as("QA D659: the reminder names the patient, as the first notice does")
            .allSatisfy(n -> assertThat(n.getMessage()).contains("Awa Femme"));
        assertThat(notificationsOf(foreignDoctor)).isEmpty();

        // The doctor acknowledges every item: the next sweep sends nothing.
        for (InBasketItem item : inBasketOf(doctor)) {
            mockMvc.perform(put(API + "/in-basket/{id}/acknowledge", item.getId()).contextPath(API)
                    .with(signedInAs(doctor)).with(csrf()))
                .andExpect(status().isOk());
        }
        long nurseBefore = notificationsOf(nurse).size();
        backdate(escalationRepository.findAll());
        sweep();
        assertThat(escalationRepository.findAll()).allSatisfy(r -> {
            assertThat(r.getEscalationLevel()).isEqualTo((short) 1);
            assertThat(r.getAcknowledgedAt()).isNotNull();
            assertThat(r.getAcknowledgedBy()).isEqualTo(doctor.user().getId());
        });
        assertThat(notificationsOf(nurse)).hasSize((int) nurseBefore);
    }

    @Test
    @DisplayName("QA D605: a later normal partograph entry ends the reminders")
    void laterNormalEntryStopsTheReminders() throws Exception {
        UUID episodeId = startEpisode();
        LocalDateTime urgentAt = LocalDateTime.now().minusMinutes(20).withNano(0);
        PartographEntryRequestDTO urgent = new PartographEntryRequestDTO();
        urgent.setObservationTime(urgentAt);
        urgent.setFetalHeartRateBpm(95);
        postEntry(episodeId, urgent);
        assertThat(escalationRepository.findAll()).isNotEmpty();

        PartographEntryRequestDTO normal = new PartographEntryRequestDTO();
        normal.setObservationTime(urgentAt.plusMinutes(15));
        normal.setFetalHeartRateBpm(140);
        postEntry(episodeId, normal);

        long nurseBefore = notificationsOf(nurse).size();
        long doctorBefore = notificationsOf(doctor).size();
        backdate(escalationRepository.findAll());
        sweep();
        assertThat(escalationRepository.findAll()).allSatisfy(r -> {
            assertThat(r.getEscalationLevel()).isZero();
            assertThat(r.getAcknowledgedAt()).isNotNull();
            assertThat(r.getAcknowledgedBy()).as("closed by the record, not a person").isNull();
        });
        assertThat(notificationsOf(nurse)).hasSize((int) nurseBefore);
        assertThat(notificationsOf(doctor)).hasSize((int) doctorBefore);
    }

    @Test
    @DisplayName("QA D658: a later newborn assessment with SpO2 only never auto-closes the temperature, HR and glucose alerts")
    void laterNewbornAssessmentWithoutTheValueKeepsTheAlertsOpen() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        UUID deliveryId = recordUneventfulDelivery(startEpisode(), now.minusMinutes(30));
        escalationRepository.deleteAllInBatch();
        com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO first =
            new com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO();
        first.setDeliveryRecordId(deliveryId);
        first.setAssessmentTime(now.minusMinutes(8));
        first.setTemperatureCelsius(35.2);
        first.setHeartRateBpm(190);
        first.setOxygenSaturationPercent(85);
        first.setGlucoseMgDl(30);
        postNewborn(first);
        List<UUID> firstRows = escalationRepository.findAll().stream().map(ObstetricAlertEscalation::getId).toList();
        assertThat(escalationRepository.findAll()).extracting(ObstetricAlertEscalation::getAlertType)
            .contains("THERMOREGULATION", "CARDIOVASCULAR", "OXYGENATION", "GLUCOSE");

        com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO second =
            new com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO();
        second.setDeliveryRecordId(deliveryId);
        second.setAssessmentTime(now.minusMinutes(5));
        second.setOxygenSaturationPercent(84);
        postNewborn(second);

        backdate(escalationRepository.findAll());
        sweep();
        assertThat(escalationRepository.findAllById(firstRows))
            .filteredOn(r -> List.of("THERMOREGULATION", "CARDIOVASCULAR", "GLUCOSE").contains(r.getAlertType()))
            .hasSize(3)
            .allSatisfy(r -> {
                assertThat(r.getAcknowledgedAt()).as(r.getAlertType() + " stays open").isNull();
                assertThat(r.getEscalationLevel()).as(r.getAlertType() + " keeps escalating").isEqualTo((short) 1);
            });
    }

    @Test
    @DisplayName("QA D658: a later partograph entry with the FHR only closes the FHR alert, never the blood-pressure one")
    void laterPartographEntryClosesOnlyWhatItRemeasured() throws Exception {
        UUID episodeId = startEpisode();
        LocalDateTime urgentAt = LocalDateTime.now().minusMinutes(20).withNano(0);
        PartographEntryRequestDTO urgent = new PartographEntryRequestDTO();
        urgent.setObservationTime(urgentAt);
        urgent.setFetalHeartRateBpm(95);
        urgent.setSystolicBpMmHg(165);
        urgent.setDiastolicBpMmHg(112);
        postEntry(episodeId, urgent);

        PartographEntryRequestDTO fhrOnly = new PartographEntryRequestDTO();
        fhrOnly.setObservationTime(urgentAt.plusMinutes(15));
        fhrOnly.setFetalHeartRateBpm(140);
        postEntry(episodeId, fhrOnly);

        backdate(escalationRepository.findAll());
        sweep();
        List<ObstetricAlertEscalation> rows = escalationRepository.findAll();
        assertThat(rows).filteredOn(r -> "FETAL_HEART_RATE".equals(r.getAlertType()))
            .isNotEmpty().allSatisfy(r -> assertThat(r.getAcknowledgedAt()).isNotNull());
        assertThat(rows).filteredOn(r -> "MATERNAL_VITALS".equals(r.getAlertType()))
            .isNotEmpty().allSatisfy(r -> {
                assertThat(r.getAcknowledgedAt()).as("BP was not re-measured").isNull();
                assertThat(r.getEscalationLevel()).isEqualTo((short) 1);
            });
    }

    private UUID recordUneventfulDelivery(UUID episodeId, LocalDateTime birth) throws Exception {
        com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO delivery =
            new com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO();
        delivery.setBirthDateTime(birth);
        delivery.setDeliveryMode(com.example.hms.enums.DeliveryMode.SPONTANEOUS_VAGINAL);
        delivery.setLiveBirth(true);
        delivery.setNumberOfInfants(1);
        delivery.setBirthWeightGrams(3100);
        delivery.setApgarOneMinute(9);
        delivery.setApgarFiveMinute(9);
        delivery.setPlacentaDeliveredAt(birth.plusMinutes(10));
        delivery.setPlacentaComplete(true);
        delivery.setUterotonicGiven(true);
        delivery.setEstimatedBloodLossMl(300);
        String body = mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/delivery", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(delivery))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private void postNewborn(com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO request)
        throws Exception {
        mockMvc.perform(post(API + "/patients/{p}/postpartum/newborn-assessments", patient.getId())
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());
    }

    private void postEntry(UUID episodeId, PartographEntryRequestDTO entry) throws Exception {
        mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/entries", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(entry))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("QA D605: escalation stops at the round cap")
    void escalationStopsAtTheCap() throws Exception {
        UUID episodeId = startEpisode();
        PartographEntryRequestDTO entry = new PartographEntryRequestDTO();
        entry.setObservationTime(LocalDateTime.now().withNano(0));
        entry.setFetalHeartRateBpm(95);
        mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/entries", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(entry))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());

        List<ObstetricAlertEscalation> rows = escalationRepository.findAll();
        assertThat(rows).isNotEmpty();
        for (ObstetricAlertEscalation row : rows) {
            row.setEscalationLevel((short) 3);
        }
        escalationRepository.saveAll(rows);
        long nurseBefore = notificationsOf(nurse).size();
        backdate(escalationRepository.findAll());
        sweep();
        assertThat(escalationRepository.findAll()).allSatisfy(r -> assertThat(r.getEscalationLevel()).isEqualTo((short) 3));
        assertThat(notificationsOf(nurse)).hasSize((int) nurseBefore);
    }

    @Test
    @DisplayName("QA D605: a lab technician alone on call never takes the first notice; admins never get patient data")
    void nonClinicalOnCallFallsBackToTheTeamAndAdminsHearNothing() throws Exception {
        Actor labTech = staffActor("labtech", "ROLE_LAB_TECHNICIAN", JobTitle.LAB_TECHNICIAN, hospital);
        Actor admin = staffActor("admin", "ROLE_HOSPITAL_ADMIN", JobTitle.HOSPITAL_ADMIN, hospital);
        onCallScheduleRepository.save(OnCallSchedule.builder()
            .staff(staffRepository.findAll().stream()
                .filter(st -> st.getUser().getId().equals(labTech.user().getId())).findFirst().orElseThrow())
            .startTime(java.time.OffsetDateTime.now().minusHours(1))
            .endTime(java.time.OffsetDateTime.now().plusHours(8))
            .build());

        UUID episodeId = startEpisode();
        PartographEntryRequestDTO entry = new PartographEntryRequestDTO();
        entry.setObservationTime(LocalDateTime.now().withNano(0));
        entry.setFetalHeartRateBpm(95);
        mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/entries", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(entry))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());

        List<ObstetricAlertEscalation> rows = escalationRepository.findAll();
        assertThat(rows).isNotEmpty();
        assertThat(notificationsOf(doctor))
            .filteredOn(n -> ObstetricAlertEscalationWorker.NOTIFICATION_TYPE.equals(n.getType()))
            .as("the doctor hears at once, not 15 minutes later").hasSize(rows.size());
        assertThat(inBasketOf(doctor)).hasSize(rows.size());
        assertThat(notificationsOf(labTech)).isEmpty();
        assertThat(inBasketOf(labTech)).isEmpty();

        backdate(rows);
        sweep();
        assertThat(escalationRepository.findAll()).allSatisfy(r -> assertThat(r.getEscalationLevel()).isEqualTo((short) 1));
        assertThat(notificationsOf(admin)).as("admins are off the chart (D5)").isEmpty();
        assertThat(inBasketOf(admin)).as("admins are off the in-basket (D5)").isEmpty();
        assertThat(notificationsOf(labTech)).isEmpty();
    }

    @Test
    @DisplayName("QA D605: the documenter alone on call still gets the first notice to the rest of the team")
    void documenterAloneOnCallStillReachesTheTeam() throws Exception {
        onCallScheduleRepository.save(OnCallSchedule.builder()
            .staff(staffRepository.findAll().stream()
                .filter(st -> st.getUser().getId().equals(midwife.user().getId())).findFirst().orElseThrow())
            .startTime(java.time.OffsetDateTime.now().minusHours(1))
            .endTime(java.time.OffsetDateTime.now().plusHours(8))
            .build());

        UUID episodeId = startEpisode();
        PartographEntryRequestDTO entry = new PartographEntryRequestDTO();
        entry.setObservationTime(LocalDateTime.now().withNano(0));
        entry.setFetalHeartRateBpm(95);
        mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/entries", patient.getId(), episodeId)
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(entry))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated());

        List<ObstetricAlertEscalation> rows = escalationRepository.findAll();
        assertThat(rows).isNotEmpty();
        assertThat(notificationsOf(doctor))
            .filteredOn(n -> ObstetricAlertEscalationWorker.NOTIFICATION_TYPE.equals(n.getType()))
            .as("the on-call documenter is skipped, so the doctor hears at once").hasSize(rows.size());
        assertThat(inBasketOf(doctor)).hasSize(rows.size());
        assertThat(inBasketOf(midwife)).as("the documenter cannot acknowledge her own alert").isEmpty();
        assertThat(notificationsOf(foreignDoctor)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private UUID startEpisode() throws Exception {
        LaborEpisodeRequestDTO request = new LaborEpisodeRequestDTO();
        request.setLaborOnsetAt(LocalDateTime.now().minusHours(3).withNano(0));
        // QA D796: no pregnancy is on record for this patient, so the
        // clinician's estimated gestational age opens it with the episode.
        request.setGestationalAgeWeeks(39);
        String body = mockMvc.perform(post(API + "/patients/{p}/labor/episodes", patient.getId())
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private List<Notification> notificationsOf(Actor actor) {
        return notificationRepository.findAll().stream()
            .filter(n -> actor.user().getUsername().equals(n.getRecipientUsername()))
            .toList();
    }

    private List<InBasketItem> inBasketOf(Actor actor) {
        return inBasketItemRepository.findAll().stream()
            .filter(i -> ObstetricAlertEscalationWorker.REFERENCE_TYPE.equals(i.getReferenceType()))
            .filter(i -> actor.user().getId().equals(i.getRecipientUser().getId()))
            .toList();
    }

    private void backdate(List<ObstetricAlertEscalation> rows) {
        for (ObstetricAlertEscalation row : rows) {
            row.setLastEscalationAt(LocalDateTime.now().minusMinutes(30));
        }
        escalationRepository.saveAll(rows);
    }

    /**
     * The real sweep behind its real ShedLock. The lock holds for
     * lockAtLeastFor (5 s) after any run, this class's or the scheduler's, and
     * a run inside it is skipped silently, so wait it out first.
     */
    private void sweep() {
        java.util.concurrent.locks.LockSupport.parkNanos(6_000_000_000L);
        scheduler.runSweep();
    }

    private static RequestPostProcessor signedInAs(Actor actor) {
        CustomUserDetails details = new CustomUserDetails(actor.user().getId(), actor.user().getUsername(), "n/a", true,
            List.of(new SimpleGrantedAuthority(actor.role())));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private void wipe() {
        escalationRepository.deleteAllInBatch();
        onCallScheduleRepository.deleteAllInBatch();
        // The delivery in the D658 newborn test raises its charge (D608) and opens a postpartum plan.
        applicationContext.getBean(com.example.hms.repository.InvoiceItemRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.BillingInvoiceRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.PostpartumObservationRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.NewbornAssessmentRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.PostpartumCarePlanRepository.class).deleteAll();
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name)
            .code("H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226555" + id)
            .email("contact" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private Actor staffActor(String prefix, String roleCode, JobTitle jobTitle, Hospital at) {
        Role role = roleRepository.findByCode(roleCode)
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name(roleCode.substring("ROLE_".length()))
                .code(roleCode)
                .description(roleCode)
                .build()));
        User user = userRepository.save(buildUser(prefix));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description(prefix + " at " + at.getName())
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        staffRepository.save(Staff.builder()
            .user(user)
            .hospital(at)
            .assignment(assignment)
            .jobTitle(jobTitle)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name(prefix + " " + user.getLastName())
            .active(true)
            .build());
        return new Actor(user, assignment, roleCode);
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
        User patientUser = userRepository.save(buildUser("patient"));
        String suffix = nextId();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Femme")
            .dateOfBirth(LocalDate.of(1996, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("awa" + suffix + "@patient.test")
            .emergencyContactName("Issa")
            .emergencyContactPhone("+22679" + suffix)
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .user(patientUser)
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved)
            .hospital(hospital)
            .mrn("MRN-" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
        return saved;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
