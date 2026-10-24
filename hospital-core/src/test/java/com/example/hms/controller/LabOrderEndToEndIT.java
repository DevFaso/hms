package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Notification;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.CriticalValueReadBackRequestDTO;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hospital → laboratory → ordering doctor, through the REAL filter chain and
 * the real services on H2.
 *
 * <p>The audit that led here found the pieces individually correct and the
 * flow broken: nothing advanced {@code LabOrderStatus}, so a released result
 * never reached the doctor's review queue (B2); the edge matcher 403'd roles
 * the annotation admits (B8); release admitted the ordering roles the
 * authority class excludes (B10) and skipped the tenancy guard (B11); and
 * normal results were auto-released with no human (B5). Each step below is
 * one of those, asserted at the HTTP boundary.
 */
@AutoConfigureMockMvc
class LabOrderEndToEndIT extends BaseIT {

    private static final String API = "/api";
    private static final String LAB_ORDERS = API + "/lab-orders";
    private static final String LAB_RESULTS = API + "/lab-results";
    private static final String LAB_ORDER_SPECIMENS = LAB_ORDERS + "/{id}/specimens";
    private static final String SPECIMEN_RECEIVE = API + "/lab-specimens/{id}/receive";
    private static final String RESULT_RELEASE = LAB_RESULTS + "/{id}/release";
    private static final String RESULT_ACKNOWLEDGE = LAB_RESULTS + "/{id}/acknowledge";
    private static final String RESULT_READ_BACK = LAB_RESULTS + "/{id}/critical-read-back";
    private static final String REVIEW_QUEUE = API + "/me/results/review-queue";

    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_LAB_TECHNICIAN = "ROLE_LAB_TECHNICIAN";
    private static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";
    private static final String ROLE_LAB_DIRECTOR = "ROLE_LAB_DIRECTOR";
    private static final String ROLE_QUALITY_MANAGER = "ROLE_QUALITY_MANAGER";

    /** Reference range 0-10 with a critical limit of 20 (QA D313: out of range alone is HIGH, not critical). */
    private static final double RANGE_MAX = 10.0;
    private static final double CRITICAL_HIGH = 20.0;
    private static final String NORMAL_VALUE = "5";
    private static final String CRITICAL_VALUE = "50";

    private final AtomicInteger sequence = new AtomicInteger();

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
    @Autowired private LabTestDefinitionRepository labTestDefinitionRepository;
    @Autowired private LabOrderRepository labOrderRepository;
    @Autowired private LabResultRepository labResultRepository;
    @Autowired private NotificationRepository notificationRepository;

    private Organization organization;
    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private LabTestDefinition testDefinition;

    private Actor doctor;
    private Actor nurse;
    private Actor technician;
    private Actor scientist;
    private Actor director;
    private Actor qualityManager;
    private Actor labManager;
    private Actor foreignScientist;

    /** A signed-in person: the user row, their single assignment, and the authority the JWT would carry. */
    private record Actor(User user, UserRoleHospitalAssignment assignment, String role) {
        UUID assignmentId() {
            return assignment.getId();
        }
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
        hospital = saveHospital("Ordering Hospital");
        otherHospital = saveHospital("Somewhere Else");

        doctor = actor("doctor", ROLE_DOCTOR, hospital);
        nurse = actor("nurse", ROLE_NURSE, hospital);
        technician = actor("tech", ROLE_LAB_TECHNICIAN, hospital);
        scientist = actor("scientist", ROLE_LAB_SCIENTIST, hospital);
        director = actor("director", ROLE_LAB_DIRECTOR, hospital);
        qualityManager = actor("qm", ROLE_QUALITY_MANAGER, hospital);
        labManager = actor("labmgr", "ROLE_LAB_MANAGER", hospital);
        foreignScientist = actor("foreign", ROLE_LAB_SCIENTIST, otherHospital);

        staffRepository.save(Staff.builder()
            .user(doctor.user())
            .hospital(hospital)
            .assignment(doctor.assignment())
            .jobTitle(JobTitle.DOCTOR)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Dr. " + doctor.user().getFirstName())
            .active(true)
            .build());

        patient = createPatient();

        testDefinition = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("K-" + nextId())
            .name("Potassium")
            .unit("mmol/L")
            .referenceRanges(List.of(LabTestReferenceRange.builder()
                .minValue(0.0).maxValue(RANGE_MAX).criticalHigh(CRITICAL_HIGH).unit("mmol/L").build()))
            .approvalStatus(com.example.hms.enums.LabTestDefinitionApprovalStatus.ACTIVE)
            .build());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // The H2 database is shared with the sibling ITs, whose own cleanup
        // does not know about lab orders: an order left here would pin the
        // doctor's staff row, which pins the assignment, and their
        // deleteAll() fails on the FK. Leave nothing behind.
        wipe();
    }

    // ── the flow ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("order → specimen → result → release → review queue, with every gate on the way")
    void orderTravelsFromTheDoctorToTheLabAndBack() throws Exception {
        // 1. The doctor places the order.
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(3));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);

        // 2. The technician collects and receives the specimen (B2: each
        //    event is the order's state, no transition call needed).
        String specimenId = jsonData(mockMvc.perform(post(LAB_ORDER_SPECIMENS, orderId)
                .contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(LabSpecimenRequestDTO.builder().specimenType("BLOOD").build()))
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn(), "id");
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.COLLECTED);

        mockMvc.perform(post(SPECIMEN_RECEIVE, specimenId).contextPath(API)
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isOk());
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RECEIVED);

        // 3. The scientist posts a result: the order is RESULTED, and the
        //    normal-range value is NOT released behind anyone's back (B5).
        UUID resultId = postResult(scientist, orderId, NORMAL_VALUE).andExpect(status().isCreated())
            .andExpect(jsonPath("$.released").value(false))
            .andReturn().getResponse().getContentAsString().transform(this::idOf);
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RESULTED);
        assertThat(labResultRepository.findById(resultId).orElseThrow().isReleased()).isFalse();

        // 3b. B8: the roles the annotation admits get past the edge too. They
        //     post on a second order so the queue assertion below stays exact.
        //     QA D708: one result per order, so each role posts on its own.
        UUID secondOrderId = resultedOrderPostedBy(director);
        resultedOrderPostedBy(qualityManager);

        // 4. B10: the ordering roles cannot release — the annotation says so
        //    before the service is reached.
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(nurse)).with(csrf()))
            .andExpect(status().isForbidden());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isReleased()).isFalse();

        // 5. The scientist who entered the result releases it (D58): one click
        //    is refused, because a self-release must be explicit...
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(scientist)).with(csrf()))
            .andExpect(status().isConflict());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isReleased()).isFalse();
        //    ...and the confirmed self-release goes through, marked as one. The
        //    only result of the order is now released, so the order is
        //    COMPLETED (B2).
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).param("selfRelease", "true")
                .with(signedInAs(scientist)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.released").value(true))
            .andExpect(jsonPath("$.selfReleased").value(true));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.COMPLETED);
        // QA D638 self-release row: the release's own audit row is in the table,
        // anchored at the hospital and naming the patient (ids only).
        assertThat(applicationContext.getBean(com.example.hms.repository.AuditEventLogRepository.class).findAll())
            .anyMatch(row -> row.getEventType() == com.example.hms.enums.AuditEventType.LAB_RESULT_SELF_RELEASED
                && resultId.toString().equals(row.getResourceId())
                && patient.getId().equals(row.getPatientId())
                && hospital.getName().equals(row.getHospitalName()));
        // the second order still has its unreleased result
        assertThat(orderStatus(secondOrderId)).isEqualTo(LabOrderStatus.RESULTED);

        // 6. The ordering doctor's review queue now shows exactly that result.
        mockMvc.perform(get(REVIEW_QUEUE).contextPath(API).with(signedInAs(doctor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(resultId.toString()))
            .andExpect(jsonPath("$.data[0].patientId").value(patient.getId().toString()))
            .andExpect(jsonPath("$.data[0].resultValue").value(NORMAL_VALUE));
    }

    @Test
    @DisplayName("QA D708: a second post on a single-test order is refused; the first result stands")
    void aSecondPostOnTheSameOrderIsRefused() throws Exception {
        // QA D708: two POSTs 240 ms apart both got 201 on a TDR order; one was
        // never released, so the patient saw a permanent "pending" row and the
        // chart and record PDF listed the result twice. An order is one test,
        // one value: an unreleased result is edited, a released one corrected.
        UUID orderId = placeOrder(LocalDateTime.now().minusMinutes(20));
        collectSpecimen(orderId);
        LocalDateTime resultedAt = LocalDateTime.now().withNano(0);

        String first = postResult(scientist, orderId, NORMAL_VALUE, resultedAt)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.labTestName").value("Potassium"))
            .andReturn().getResponse().getContentAsString();
        postResult(scientist, orderId, NORMAL_VALUE, resultedAt).andExpect(status().isConflict());

        assertThat(labResultRepository.findAll()).hasSize(1)
            .allMatch(row -> row.getId().equals(idOf(first)));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("B11 — a lab scientist of another hospital neither sees the order nor can attach a result to it")
    void foreignLaboratoryStaffSeeNothing() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(1));

        mockMvc.perform(get(LAB_ORDERS + "/{id}", orderId).contextPath(API).with(signedInAs(foreignScientist)))
            .andExpect(status().isNotFound());

        postResult(foreignScientist, orderId, NORMAL_VALUE).andExpect(status().isNotFound());

        assertThat(labResultRepository.findAll()).isEmpty();
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);
    }

    @Test
    @DisplayName("a critical value notifies the doctor, stays unreleased, and is acknowledged only by a matching read-back")
    void criticalValueClosesTheLoopThroughReadBack() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusMinutes(30));
        collectSpecimen(orderId);

        UUID resultId = postResult(scientist, orderId, CRITICAL_VALUE).andExpect(status().isCreated())
            .andExpect(jsonPath("$.severityFlag").value("CRITICAL")) // QA D206: paged, so labelled critical
            .andExpect(jsonPath("$.released").value(false))
            .andReturn().getResponse().getContentAsString().transform(this::idOf);

        // QA D709: nobody is paged about a result they cannot read yet, and
        // the ordering side can neither read it back nor acknowledge it.
        LabResult draft = labResultRepository.findById(resultId).orElseThrow();
        assertThat(draft.isReleased()).isFalse();
        assertThat(draft.getCriticalNotifiedAt()).isNull();
        assertThat(notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(doctor.user().getUsername(), false)).isEmpty();
        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack(CRITICAL_VALUE)))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post(RESULT_ACKNOWLEDGE, resultId).contextPath(API).with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isNotFound());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isAcknowledged()).isFalse();

        // The release pages the doctor.
        releaseAs(director, resultId);
        LabResult stored = labResultRepository.findById(resultId).orElseThrow();
        assertThat(stored.isReleased()).isTrue();
        assertThat(stored.getCriticalNotifiedAt()).isNotNull();
        List<Notification> forDoctor = notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(doctor.user().getUsername(), false);
        assertThat(forDoctor).hasSize(1);
        assertThat(forDoctor.get(0).getType()).isEqualTo("CRITICAL_LAB_RESULT");

        // Dismissing the alert is not acknowledging a critical value.
        mockMvc.perform(post(RESULT_ACKNOWLEDGE, resultId).contextPath(API).with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isAcknowledged()).isFalse();

        // A wrong read-back is rejected (and recorded), a right one resolves it.
        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack("51")))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(labResultRepository.findById(resultId).orElseThrow().isAcknowledged()).isFalse();

        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack(CRITICAL_VALUE)))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));
        LabResult acknowledged = labResultRepository.findById(resultId).orElseThrow();
        assertThat(acknowledged.isAcknowledged()).isTrue();
        assertThat(acknowledged.getCriticalReadBackAt()).isNotNull();
        assertThat(acknowledged.getAcknowledgedByUserId()).isEqualTo(doctor.user().getId());
    }

    @Test
    @DisplayName("QA D402: the real sweep escalates a still-critical result and drops only a no-longer-critical one")
    void sweepEscalatesAStillCriticalResultAndDropsOnlyANormalOne() throws Exception {
        UUID criticalOrder = placeOrder(LocalDateTime.now().minusMinutes(90));
        collectSpecimen(criticalOrder);
        UUID critical = idOf(postResult(scientist, criticalOrder, CRITICAL_VALUE).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        UUID normalOrder = placeOrder(LocalDateTime.now().minusMinutes(90));
        collectSpecimen(normalOrder);
        UUID normal = idOf(postResult(scientist, normalOrder, NORMAL_VALUE).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());

        // Both carry a page 35 minutes old: the critical one because it paged,
        // the normal one as the stale stamp of D305 (a value amended since).
        LocalDateTime paged = LocalDateTime.now().minusMinutes(35);
        for (UUID id : List.of(critical, normal)) {
            LabResult row = labResultRepository.findById(id).orElseThrow();
            row.setCriticalNotifiedAt(paged);
            labResultRepository.save(row);
        }

        // The scheduled sweep: no request, no security context, lazy order proxies.
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        com.example.hms.service.CriticalValueNotificationService sweep =
            applicationContext.getBean(com.example.hms.service.CriticalValueNotificationService.class);
        assertThat(runSweep(sweep)).isEqualTo(1);

        LabResult escalated = labResultRepository.findById(critical).orElseThrow();
        assertThat(escalated.getCriticalNotifiedAt()).isNotNull();
        assertThat(escalated.getCriticalEscalationLevel()).isEqualTo((short) 1);
        assertThat(escalated.getCriticalEscalatedAt()).isNotNull();
        assertThat(notificationRepository
            .findByRecipientUsernameAndReadOrderByCreatedAtDesc(doctor.user().getUsername(), false))
            .anyMatch(n -> "CRITICAL_LAB_RESULT_ESCALATION".equals(n.getType()));

        LabResult dropped = labResultRepository.findById(normal).orElseThrow();
        assertThat(dropped.getCriticalNotifiedAt()).isNull();
        assertThat(dropped.getCriticalEscalationLevel()).isZero();

        // Round 2 a further interval later: still critical, so it climbs.
        escalated.setCriticalEscalatedAt(LocalDateTime.now().minusMinutes(35));
        labResultRepository.save(escalated);
        assertThat(runSweep(sweep)).isEqualTo(1);
        assertThat(labResultRepository.findById(critical).orElseThrow().getCriticalEscalationLevel())
            .isEqualTo((short) 2);
    }

    @Test
    @DisplayName("QA D415: a French read-back with a decimal comma matches the reported value")
    void readBackAcceptsTheFrenchDecimalComma() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusMinutes(30));
        collectSpecimen(orderId);
        UUID resultId = idOf(postResult(scientist, orderId, "28.5").andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        // QA D709: a read-back is of a released result.
        releaseAs(director, resultId);

        // QA D429: the write audit names the lab event and the patient, not a bare DATA_CREATE.
        assertThat(applicationContext.getBean(com.example.hms.repository.AuditEventLogRepository.class).findAll())
            .anyMatch(row -> row.getEventType() == com.example.hms.enums.AuditEventType.LAB_RESULT_CREATED
                && patient.getId().equals(row.getPatientId())
                && resultId.toString().equals(row.getResourceId()))
            .anyMatch(row -> row.getEventType() == com.example.hms.enums.AuditEventType.LAB_ORDER_CREATED
                && patient.getId().equals(row.getPatientId()))
            .noneMatch(row -> row.getEventType() == com.example.hms.enums.AuditEventType.DATA_CREATE
                && String.valueOf(row.getEventDescription()).contains("/lab-"));

        mockMvc.perform(post(RESULT_READ_BACK, resultId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(readBack("28,5")))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acknowledged").value(true));
    }

    @Test
    @DisplayName("QA D638/D650: every approval step writes a named audit row; a retired definition is read-only")
    void approvalStepsAreAuditedAndARetiredDefinitionIsReadOnly() throws Exception {
        LabTestDefinition draft = labTestDefinitionRepository.save(LabTestDefinition.builder()
            .testCode("TDR-" + nextId())
            .name("R9 draft " + nextId())
            .unit("mg/L")
            .hospital(hospital)
            .referenceRanges(List.of(LabTestReferenceRange.builder()
                .minValue(0.0).maxValue(5.0).unit("mg/L").build()))
            .approvalStatus(com.example.hms.enums.LabTestDefinitionApprovalStatus.DRAFT)
            .active(false)
            .build());
        UUID id = draft.getId();

        approval(id, "SUBMIT_FOR_QA", scientist);
        approval(id, "COMPLETE_QA_REVIEW", qualityManager);
        approval(id, "APPROVE", director);
        approval(id, "RETIRE", director);

        List<com.example.hms.model.AuditEventLog> steps = applicationContext
            .getBean(com.example.hms.repository.AuditEventLogRepository.class).findAll().stream()
            .filter(row -> id.toString().equals(row.getResourceId())
                && row.getEventType() == com.example.hms.enums.AuditEventType.CONFIGURATION_CHANGED)
            .toList();
        assertThat(steps).hasSize(4)
            .allMatch(row -> hospital.getName().equals(row.getHospitalName()));
        assertThat(steps).extracting(com.example.hms.model.AuditEventLog::getEventDescription)
            .anyMatch(d -> d.contains("SUBMIT_FOR_QA"))
            .anyMatch(d -> d.contains("COMPLETE_QA_REVIEW"))
            .anyMatch(d -> d.contains("APPROVE"))
            .anyMatch(d -> d.contains("RETIRE"));

        // D650: the QA call - PUT the definition back unchanged - and the ranges sibling.
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("testCode", draft.getTestCode());
        body.put("testName", draft.getName());
        body.put("unit", "mg/L");
        mockMvc.perform(put(API + "/lab-test-definitions/{id}", id).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body))
                .with(signedInAs(director)).with(csrf()))
            .andExpect(status().isConflict());
        mockMvc.perform(put(API + "/lab-test-definitions/{id}/reference-ranges", id).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"minValue\":0.0,\"maxValue\":5.0,\"unit\":\"mg/L\"}]")
                .with(signedInAs(director)).with(csrf()))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("QA D648/D645: a result whose definition was deleted stays readable everywhere, and its order can be cancelled")
    void resultOfADeletedDefinitionStaysReadable() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(2));
        collectSpecimen(orderId);
        UUID resultId = idOf(postResult(scientist, orderId, CRITICAL_VALUE).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(director)).with(csrf()))
            .andExpect(status().isOk());
        UUID strandedOrderId = placeOrder(LocalDateTime.now().minusHours(1));
        UUID definitionId = testDefinition.getId();

        // The rows D601 deleted on dev before the fix: no FK on Postgres, so the orders kept the id.
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbc.update("DELETE FROM lab.lab_test_definitions WHERE id = ?", definitionId);
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }

        // The QA calls (t4.js / t5.js), as the doctor.
        mockMvc.perform(get(LAB_RESULTS + "/{id}", resultId).contextPath(API).with(signedInAs(doctor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultValue").value(CRITICAL_VALUE))
            .andExpect(jsonPath("$.labTestDefinitionMissing").value(true))
            .andExpect(jsonPath("$.labTestName").isNotEmpty());
        assertThat(mockMvc.perform(get(API + "/patients/{pid}/lab-results", patient.getId()).contextPath(API)
                .with(signedInAs(doctor)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .contains(resultId.toString());
        assertThat(mockMvc.perform(get(LAB_RESULTS + "/hospital/{hid}/critical", hospital.getId()).contextPath(API)
                .with(signedInAs(doctor)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .contains(resultId.toString());

        // D645: the /lab worklist labels both orders instead of a dash.
        String orders = mockMvc.perform(get(LAB_ORDERS).contextPath(API).param("size", "50")
                .with(signedInAs(technician)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        tools.jackson.databind.JsonNode content = objectMapper.readTree(orders).path("data").path("content");
        assertThat(content.size()).isEqualTo(2);
        content.forEach(o -> {
            assertThat(o.path("labTestDefinitionMissing").asBoolean()).isTrue();
            assertThat(o.path("labTestName").asText()).isNotBlank();
        });

        // ...and the stranded ORDERED order can be resolved (cancelled), keeping its column.
        mockMvc.perform(post(LAB_ORDERS + "/{id}/transition", strandedOrderId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"Test definition withdrawn\"}")
                .with(signedInAs(labManager)).with(csrf()))
            .andExpect(status().isOk());
        assertThat(orderStatus(strandedOrderId)).isEqualTo(LabOrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT lab_test_definition_id FROM lab.lab_orders WHERE id = ?",
            UUID.class, strandedOrderId)).isEqualTo(definitionId);
    }

    @Test
    @DisplayName("QA D666/D673: a cancel needs a stated reason, is kept and audited, and a hospital admin cannot cancel")
    void cancellationNeedsAReasonAndIsAudited() throws Exception {
        // The QA calls (r9-retest/lab3/d666.js): POST /lab-orders/{id}/transition.
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(1));
        Actor hospitalAdmin = actor("hadmin", "ROLE_HOSPITAL_ADMIN", hospital);

        // D666 decision (E9 #67): HOSPITAL_ADMIN does not cancel a clinical order.
        mockMvc.perform(post(LAB_ORDERS + "/{id}/transition", orderId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"QA R9 rt3 test supprimé\"}")
                .with(signedInAs(hospitalAdmin)).with(csrf()))
            .andExpect(status().isForbidden());

        // D673: no reason, then a blank one: a translated 400, the order untouched.
        mockMvc.perform(post(LAB_ORDERS + "/{id}/transition", orderId).contextPath(API)
                .header("Accept-Language", "fr")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}")
                .with(signedInAs(director)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Un motif est requis")));
        mockMvc.perform(post(LAB_ORDERS + "/{id}/transition", orderId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"  \"}")
                .with(signedInAs(director)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);

        mockMvc.perform(post(LAB_ORDERS + "/{id}/transition", orderId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"QA R9 rt3 annulation directeur\"}")
                .with(signedInAs(director)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cancellationReason").value("QA R9 rt3 annulation directeur"));
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.CANCELLED);
        org.springframework.jdbc.core.JdbcTemplate jdbc =
            applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM lab.lab_orders WHERE id = ?",
            String.class, orderId)).isEqualTo("QA R9 rt3 annulation directeur");
        assertThat(applicationContext.getBean(com.example.hms.repository.AuditEventLogRepository.class).findAll())
            .anyMatch(row -> row.getEventType() == com.example.hms.enums.AuditEventType.LAB_ORDER_CANCELLED
                && orderId.toString().equals(row.getResourceId())
                && patient.getId().equals(row.getPatientId()));
    }

    @Test
    @DisplayName("QA D649: a specimen collected in the future is refused")
    void futureCollectionIsRefused() throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(1));
        mockMvc.perform(post(LAB_ORDER_SPECIMENS, orderId)
                .contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(LabSpecimenRequestDTO.builder()
                    .specimenType("BLOOD").collectedAt(LocalDateTime.now().plusHours(48)).build()))
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.ORDERED);
    }

    private void approval(UUID definitionId, String action, Actor actor) throws Exception {
        mockMvc.perform(post(API + "/lab-test-definitions/{id}/approval", definitionId).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"" + action + "\"}")
                .with(signedInAs(actor)).with(csrf()))
            .andExpect(status().isOk());
    }

    /**
     * The sweep behind its real ShedLock: a run inside the previous run's
     * lockAtLeastFor window (or the scheduler's) is skipped and answers null,
     * so wait for the lock rather than read a skip as "nothing escalated".
     */
    private static Integer runSweep(com.example.hms.service.CriticalValueNotificationService sweep) {
        long deadline = System.currentTimeMillis() + 20_000;
        Integer escalated = sweep.escalateOverdue();
        while (escalated == null && System.currentTimeMillis() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(500_000_000L);
            escalated = sweep.escalateOverdue();
        }
        return escalated;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    /** Places and collects a fresh order, posts a normal result on it as {@code actor}, and checks it is RESULTED. */
    private UUID resultedOrderPostedBy(Actor actor) throws Exception {
        UUID orderId = placeOrder(LocalDateTime.now().minusHours(2));
        collectSpecimen(orderId);
        postResult(actor, orderId, NORMAL_VALUE).andExpect(status().isCreated());
        assertThat(orderStatus(orderId)).isEqualTo(LabOrderStatus.RESULTED);
        return orderId;
    }

    /** Releases a result as someone other than its author (no self-release confirmation). */
    private void releaseAs(Actor actor, UUID resultId) throws Exception {
        mockMvc.perform(post(RESULT_RELEASE, resultId).contextPath(API).with(signedInAs(actor)).with(csrf()))
            .andExpect(status().isOk());
    }

    /** D107: a hand-entered result needs a collected specimen first. */
    private void collectSpecimen(UUID orderId) throws Exception {
        mockMvc.perform(post(LAB_ORDER_SPECIMENS, orderId)
                .contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(LabSpecimenRequestDTO.builder().specimenType("BLOOD").build()))
                .with(signedInAs(technician)).with(csrf()))
            .andExpect(status().isCreated());
    }

    private UUID placeOrder(LocalDateTime orderedAt) throws Exception {
        LabOrderRequestDTO request = LabOrderRequestDTO.builder()
            .patientId(patient.getId())
            .hospitalId(hospital.getId())
            .testName(testDefinition.getName())
            .status(LabOrderStatus.ORDERED.name())
            .clinicalIndication("Muscle weakness, on diuretics")
            .medicalNecessityNote("Electrolyte monitoring")
            .primaryDiagnosisCode("E87.6")
            .orderChannel("ELECTRONIC")
            .documentationSharedWithLab(true)
            .providerSignature("signed-by-" + doctor.user().getUsername())
            .orderDatetime(orderedAt)
            .orderingStaffId(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(doctor.user().getId()).orElseThrow().getId())
            .labTestDefinitionId(testDefinition.getId())
            .assignmentId(doctor.assignmentId())
            .build();
        MvcResult created = mockMvc.perform(post(LAB_ORDERS).contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request))
                .with(signedInAs(doctor)).with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("ORDERED"))
            .andReturn();
        return UUID.fromString(jsonData(created, "id"));
    }

    private org.springframework.test.web.servlet.ResultActions postResult(Actor actor, UUID orderId, String value) throws Exception {
        return postResult(actor, orderId, value, LocalDateTime.now());
    }

    /** A retry resends the identical payload, result date included. */
    private org.springframework.test.web.servlet.ResultActions postResult(
            Actor actor, UUID orderId, String value, LocalDateTime resultDate) throws Exception {
        LabResultRequestDTO request = LabResultRequestDTO.builder()
            .labOrderId(orderId)
            .assignmentId(actor.assignmentId())
            .patientId(patient.getId())
            .resultValue(value)
            .resultUnit("mmol/L")
            .resultDate(resultDate)
            .build();
        return mockMvc.perform(post(LAB_RESULTS).contextPath(API)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request))
            .with(signedInAs(actor)).with(csrf()));
    }

    private static CriticalValueReadBackRequestDTO readBack(String value) {
        CriticalValueReadBackRequestDTO request = new CriticalValueReadBackRequestDTO();
        request.setRepeatedValue(value);
        return request;
    }

    private LabOrderStatus orderStatus(UUID orderId) {
        return labOrderRepository.findById(orderId).orElseThrow().getStatus();
    }

    /** {@code $.data.<field>} of an ApiResponseWrapper body. */
    private String jsonData(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path(field).asText();
    }

    /** {@code $.id} of a bare DTO body. */
    private UUID idOf(String body) {
        try {
            return UUID.fromString(objectMapper.readTree(body).path("id").asText());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("No id in response body: " + body, ex);
        }
    }

    /** A signed-in user as the JWT filter builds one; the hospital scope resolves from their single assignment. */
    private static RequestPostProcessor signedInAs(Actor actor) {
        CustomUserDetails details = new CustomUserDetails(actor.user().getId(), actor.user().getUsername(), "n/a", true,
            List.of(new SimpleGrantedAuthority(actor.role())));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    // ── seeding ──────────────────────────────────────────────────────────

    private void wipe() {
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

    private Actor actor(String prefix, String roleCode, Hospital at) {
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
            .firstName("Aminata")
            .lastName("Diallo")
            .dateOfBirth(LocalDate.of(1992, 3, 10))
            .gender("F")
            .address("Patient address")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22678" + suffix)
            .email("aminata" + suffix + "@patient.test")
            .emergencyContactName("Issa Diallo")
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
