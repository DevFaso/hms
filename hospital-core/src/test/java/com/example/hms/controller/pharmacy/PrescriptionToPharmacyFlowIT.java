package com.example.hms.controller.pharmacy;

import com.example.hms.BaseIT;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
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
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end, over HTTP, the three ways a prescription reaches a pharmacy and
 * the pharmacy's answer coming back:
 * <ol>
 *   <li>in-house: sign, work queue, partial then completing dispense;</li>
 *   <li>partner: route-to-partner, offer SMS, replies through the webhook;</li>
 *   <li>community: SMS dispatch (G1) — the same reply machinery applies;</li>
 * </ol>
 * plus tenancy (a pharmacist at another hospital sees 404s and their partner's
 * reply is ignored) and the G8 sender binding (an unknown phone quoting a valid
 * token is ignored).
 *
 * <p>Statuses are read back from the repositories rather than from the HTTP
 * bodies so that a DTO change cannot make a wrong state look right.
 */
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "pharmacy.partner.webhook.secret=" + PrescriptionToPharmacyFlowIT.WEBHOOK_SECRET)
class PrescriptionToPharmacyFlowIT extends BaseIT {

    static final String WEBHOOK_SECRET = "flow-it-secret";

    private static final String API = "/api";
    private static final String WORK_QUEUE = API + "/pharmacy/dispense/work-queue";
    private static final String WORK_QUEUE_IDS = "$.data.content[*].id";
    private static final String DISPENSE = API + "/pharmacy/dispense";
    private static final String ROUTE_TO_PARTNER = API + "/pharmacy/routing/route-to-partner";
    private static final String WEBHOOK = API + "/webhooks/partner-sms";
    private static final String SIGNATURE_HEADER = "X-HMS-Partner-Signature";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_PHARMACIST = "ROLE_PHARMACIST";
    private static final String MEDICATION = "Amoxicillin";
    private static final String PARTNER_A_PHONE = "+22670000001";
    private static final String COMMUNITY_A_PHONE = "+22670000002";
    private static final String PARTNER_B_PHONE = "+22670000003";
    private static final String UNKNOWN_PHONE = "+22699999999";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private PharmacyRepository pharmacyRepository;
    @Autowired private PrescriptionRepository prescriptionRepository;
    @Autowired private EncounterRepository encounterRepository;
    @Autowired private PrescriptionRoutingDecisionRepository routingDecisionRepository;
    @Autowired private PrescriptionTransmissionRepository transmissionRepository;
    @Autowired private DispenseRepository dispenseRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;

    /** Every outbound SMS lands here; the offer token is read from it. */
    @MockitoBean private SmsService smsService;

    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User doctorA;
    private User pharmacistA;
    private User pharmacistB;
    private Patient patient;
    private Pharmacy dispensaryA;
    private Pharmacy partnerA;
    private Pharmacy communityA;
    private Pharmacy partnerB;

    /** Rows this test created, popped in reverse so FKs never get in the way. */
    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    @BeforeEach
    void seed() {
        auditEventLogRepository.deleteAllInBatch();

        organization = organizationRepository.save(Organization.builder()
                .name("Flow IT Network " + nextId())
                .code("FLOW-" + nextId())
                .type(OrganizationType.HOSPITAL_CHAIN)
                .active(true)
                .build());
        cleanup.push(() -> organizationRepository.deleteById(organization.getId()));

        hospitalA = seedHospital("Flow Hospital A");
        hospitalB = seedHospital("Flow Hospital B");

        Role doctorRole = ensureRole(ROLE_DOCTOR, "Doctor");
        Role pharmacistRole = ensureRole(ROLE_PHARMACIST, "Pharmacist");

        doctorA = seedUser("flowdoc");
        UserRoleHospitalAssignment doctorAssignment = seedAssignment(doctorA, hospitalA, doctorRole);
        seedStaff(doctorA, hospitalA, doctorAssignment);

        pharmacistA = seedUser("flowpharma");
        seedAssignment(pharmacistA, hospitalA, pharmacistRole);
        pharmacistB = seedUser("flowpharmb");
        seedAssignment(pharmacistB, hospitalB, pharmacistRole);

        patient = seedPatient();

        dispensaryA = seedPharmacy(hospitalA, "Dispensary A", PharmacyType.HOSPITAL_DISPENSARY, "+22670000000");
        partnerA = seedPharmacy(hospitalA, "Partner A", PharmacyType.PARTNER_PHARMACY, PARTNER_A_PHONE);
        communityA = seedPharmacy(hospitalA, "Community A", PharmacyType.COMMUNITY_PHARMACY, COMMUNITY_A_PHONE);
        partnerB = seedPharmacy(hospitalB, "Partner B", PharmacyType.PARTNER_PHARMACY, PARTNER_B_PHONE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // Hospital-scoped writes are audited with the actor's assignment id: the
        // audit rows go first, then everything this test created, newest first.
        auditEventLogRepository.deleteAllInBatch();
        while (!cleanup.isEmpty()) {
            cleanup.pop().run();
        }
    }

    // ───────────────────────── leg 1: in-house ─────────────────────────

    @Test
    @DisplayName("in-house: sign → work queue → partial fill → completing (substituted) dispense → DISPENSED")
    void inHouseDispenseFlow() throws Exception {
        UUID rxId = createAndSignPrescription();

        mockMvc.perform(apiGet(WORK_QUEUE).with(pharmacist(pharmacistA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WORK_QUEUE_IDS, hasItem(rxId.toString())));

        mockMvc.perform(apiPost(DISPENSE)
                        .with(pharmacist(pharmacistA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispenseBody(rxId, dispensaryA, pharmacistA, 4, null)))
                .andExpect(status().isCreated());
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTIALLY_FILLED);

        mockMvc.perform(apiPost(DISPENSE)
                        .with(pharmacist(pharmacistA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispenseBody(rxId, dispensaryA, pharmacistA, 6, "generic of the same strength")))
                .andExpect(status().isCreated());
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.DISPENSED);

        assertThat(auditEventLogRepository.findAll())
                .anyMatch(row -> row.getEventType() == AuditEventType.DISPENSE_SUBSTITUTED);

        mockMvc.perform(apiGet(WORK_QUEUE).with(pharmacist(pharmacistA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WORK_QUEUE_IDS, not(hasItem(rxId.toString()))));
    }

    @Test
    @DisplayName("G12: an in-house dispense cannot be booked against a partner or community pharmacy row")
    void inHouseDispenseRefusesNonDispensary() throws Exception {
        UUID rxId = createAndSignPrescription();

        mockMvc.perform(apiPost(DISPENSE)
                        .with(pharmacist(pharmacistA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispenseBody(rxId, communityA, pharmacistA, 10, null)))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SIGNED);
        assertThat(dispenseRepository.findByPrescriptionId(rxId, org.springframework.data.domain.Pageable.unpaged()))
                .isEmpty();
    }

    // ───────────────────────── leg 2: partner ─────────────────────────

    @Test
    @DisplayName("partner: route → SENT_TO_PARTNER + PENDING decision + offer SMS; '1 ref' accepts, '3 ref' confirms dispense")
    void partnerRoutingAndSmsReplies() throws Exception {
        UUID rxId = createAndSignPrescription();

        UUID decisionId = routeToPartner(rxId, partnerA);
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        PrescriptionRoutingDecision decision = routingDecisionRepository.findById(decisionId).orElseThrow();
        assertThat(decision.getRoutingType()).isEqualTo(RoutingType.PARTNER);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);

        String token = tokenFromOfferSentTo(PARTNER_A_PHONE);
        assertThat(token).isEqualTo(decisionId.toString().substring(0, 8).toUpperCase(Locale.ROOT));

        mockMvc.perform(webhook(PARTNER_A_PHONE, "1 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.decisionStatus").value("ACCEPTED"));
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);

        mockMvc.perform(webhook(PARTNER_A_PHONE, "3 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.decisionStatus").value("COMPLETED"));
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTNER_DISPENSED);

        mockMvc.perform(apiGet(WORK_QUEUE).with(pharmacist(pharmacistA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WORK_QUEUE_IDS, not(hasItem(rxId.toString()))));
    }

    @Test
    @DisplayName("partner: the staff path partner-respond?accepted=false → PARTNER_REJECTED")
    void partnerStaffRejection() throws Exception {
        UUID rxId = createAndSignPrescription();
        UUID decisionId = routeToPartner(rxId, partnerA);

        mockMvc.perform(apiPost(API + "/pharmacy/routing/partner-respond/{id}", decisionId)
                        .param("accepted", "false")
                        .with(pharmacist(pharmacistA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTNER_REJECTED);
    }

    // ───────────────────────── leg 3: community (G1) ─────────────────────────

    @Test
    @DisplayName("G1: dispatch-sms leaves SIGNED, records a PENDING decision, quits the work queue, and the reply applies")
    void communityDispatchIsARoutingDecision() throws Exception {
        UUID rxId = createAndSignPrescription();

        mockMvc.perform(apiPost(API + "/prescriptions/{id}/dispatch-sms", rxId)
                        .with(doctor(doctorA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("pharmacyId", communityA.getId(), "note", "urgent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.pharmacyId").value(communityA.getId().toString()));

        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        List<PrescriptionRoutingDecision> decisions = routingDecisionRepository.findByPrescriptionId(rxId);
        assertThat(decisions).hasSize(1);
        PrescriptionRoutingDecision decision = decisions.get(0);
        assertThat(decision.getRoutingType()).isEqualTo(RoutingType.PARTNER);
        assertThat(decision.getStatus()).isEqualTo(RoutingDecisionStatus.PENDING);
        cleanup.push(() -> routingDecisionRepository.deleteById(decision.getId()));

        String token = tokenFromOfferSentTo(COMMUNITY_A_PHONE);
        assertThat(token).isEqualTo(decision.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT));

        mockMvc.perform(apiGet(WORK_QUEUE).with(pharmacist(pharmacistA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WORK_QUEUE_IDS, not(hasItem(rxId.toString()))));

        // Double dispense is now impossible: the in-house path refuses the state.
        mockMvc.perform(apiPost(DISPENSE)
                        .with(pharmacist(pharmacistA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispenseBody(rxId, dispensaryA, pharmacistA, 10, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(webhook(COMMUNITY_A_PHONE, "1 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"));
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
    }

    // ───────────────────────── leg 4: tenancy ─────────────────────────

    @Test
    @DisplayName("tenancy: a pharmacist at hospital B gets 404 on A's prescription and B's partner cannot answer A's offer")
    void otherHospitalSeesNothing() throws Exception {
        UUID rxId = createAndSignPrescription();

        mockMvc.perform(apiGet(WORK_QUEUE).with(pharmacist(pharmacistB, hospitalB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(WORK_QUEUE_IDS, not(hasItem(rxId.toString()))));

        mockMvc.perform(apiPost(DISPENSE)
                        .with(pharmacist(pharmacistB, hospitalB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dispenseBody(rxId, dispensaryA, pharmacistB, 10, null)))
                .andExpect(status().isNotFound());

        mockMvc.perform(apiGet(API + "/pharmacy/dispense/prescription/{id}", rxId)
                        .with(pharmacist(pharmacistB, hospitalB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(apiGet(API + "/pharmacy/routing/decisions/prescription/{id}", rxId)
                        .with(pharmacist(pharmacistB, hospitalB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(apiPost(ROUTE_TO_PARTNER)
                        .with(pharmacist(pharmacistB, hospitalB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "prescriptionId", rxId,
                                "routingType", "PARTNER",
                                "targetPharmacyId", partnerB.getId()))))
                .andExpect(status().isNotFound());
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SIGNED);

        UUID decisionId = routeToPartner(rxId, partnerA);
        String token = tokenFromOfferSentTo(PARTNER_A_PHONE);

        mockMvc.perform(webhook(PARTNER_B_PHONE, "1 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(routingDecisionRepository.findById(decisionId).orElseThrow().getStatus())
                .isEqualTo(RoutingDecisionStatus.PENDING);
    }

    // ───────────────────────── leg 5: G8 ─────────────────────────

    @Test
    @DisplayName("G8: a reply from an unknown phone quoting a valid token is ignored")
    void replyFromUnknownPhoneIsIgnored() throws Exception {
        UUID rxId = createAndSignPrescription();
        UUID decisionId = routeToPartner(rxId, partnerA);
        String token = tokenFromOfferSentTo(PARTNER_A_PHONE);

        mockMvc.perform(webhook(UNKNOWN_PHONE, "1 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));

        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SENT_TO_PARTNER);
        assertThat(routingDecisionRepository.findById(decisionId).orElseThrow().getStatus())
                .isEqualTo(RoutingDecisionStatus.PENDING);

        // The genuine partner can still answer afterwards.
        mockMvc.perform(webhook(PARTNER_A_PHONE, "1 " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"));
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.PARTNER_ACCEPTED);
    }

    // ───────────────────────── flow helpers ─────────────────────────

    /** POST /prescriptions as the doctor, give it a quantity, POST /sign. */
    private UUID createAndSignPrescription() throws Exception {
        String created = mockMvc.perform(apiPost(API + "/prescriptions")
                        .with(doctor(doctorA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "patientId", patient.getId(),
                                "medicationName", MEDICATION,
                                "dosage", "500",
                                "frequency", "BID",
                                "duration", "5 days"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        UUID rxId = UUID.fromString(objectMapper.readTree(created).get("id").asString());
        cleanup.push(() -> {
            routingDecisionRepository.deleteAll(routingDecisionRepository.findByPrescriptionId(rxId));
            dispenseRepository.deleteAll(dispenseRepository.findByPrescriptionId(
                    rxId, org.springframework.data.domain.Pageable.unpaged()).getContent());
            transmissionRepository.deleteAll(transmissionRepository.findAll().stream()
                    .filter(t -> t.getPrescription() != null && rxId.equals(t.getPrescription().getId()))
                    .toList());
            Prescription rx = prescriptionRepository.findById(rxId).orElse(null);
            if (rx != null) {
                UUID encounterId = rx.getEncounter() != null ? rx.getEncounter().getId() : null;
                prescriptionRepository.deleteById(rxId);
                if (encounterId != null) {
                    encounterRepository.deleteById(encounterId);
                }
            }
        });

        // The request DTO carries no quantity; the dispense arithmetic needs one.
        transactionTemplate.executeWithoutResult(tx -> {
            Prescription rx = prescriptionRepository.findById(rxId).orElseThrow();
            rx.setQuantity(BigDecimal.TEN);
            rx.setQuantityUnit("tablet");
            rx.setDoseUnit("mg");
            rx.setRoute("PO");
        });

        mockMvc.perform(apiPost(API + "/prescriptions/{id}/sign", rxId)
                        .with(doctor(doctorA, hospitalA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
        assertThat(statusOf(rxId)).isEqualTo(PrescriptionStatus.SIGNED);
        return rxId;
    }

    private UUID routeToPartner(UUID rxId, Pharmacy target) throws Exception {
        String body = mockMvc.perform(apiPost(ROUTE_TO_PARTNER)
                        .with(pharmacist(pharmacistA, hospitalA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "prescriptionId", rxId,
                                "routingType", "PARTNER",
                                "targetPharmacyId", target.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("data").get("id").asString());
    }

    private String dispenseBody(UUID rxId, Pharmacy pharmacy, User dispensedBy, int quantity,
                                String substitutionReason) {
        Map<String, Object> body = new HashMap<>();
        body.put("prescriptionId", rxId);
        body.put("patientId", patient.getId());
        body.put("pharmacyId", pharmacy.getId());
        body.put("dispensedBy", dispensedBy.getId());
        body.put("medicationName", MEDICATION);
        body.put("quantityRequested", quantity);
        body.put("quantityDispensed", quantity);
        body.put("unit", "tablet");
        if (substitutionReason != null) {
            body.put("substitution", true);
            body.put("substitutionReason", substitutionReason);
        }
        return json(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(String from,
                                                                                               String body) {
        return apiPost(WEBHOOK)
                .header(SIGNATURE_HEADER, WEBHOOK_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("from", from, "body", body)));
    }

    /** The "HMS Rx &lt;TOKEN&gt; : …" offer the channel sent to this phone; the token is the second word. */
    private String tokenFromOfferSentTo(String phone) {
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(smsService, atLeastOnce()).send(eq(phone), bodies.capture());
        String offer = bodies.getAllValues().stream()
                .filter(b -> b.startsWith("HMS Rx "))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no offer SMS sent to " + phone));
        clearInvocations(smsService);
        return offer.split(" ")[2];
    }

    /** MockMvc does not apply server.servlet.context-path: every request carries it explicitly. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder apiGet(
            String path, Object... vars) {
        return get(path, vars).contextPath(API);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder apiPost(
            String path, Object... vars) {
        return post(path, vars).contextPath(API);
    }

    private PrescriptionStatus statusOf(UUID rxId) {
        return prescriptionRepository.findById(rxId).orElseThrow().getStatus();
    }

    private String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    // ───────────────────────── auth helpers ─────────────────────────

    private RequestPostProcessor doctor(User user, Hospital hospital) {
        return as(user, hospital, ROLE_DOCTOR);
    }

    private RequestPostProcessor pharmacist(User user, Hospital hospital) {
        return as(user, hospital, ROLE_PHARMACIST);
    }

    private RequestPostProcessor as(User user, Hospital hospital, String authority) {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        CustomUserDetails principal = new CustomUserDetails(
                user.getId(), user.getUsername(), user.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(), authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                    .principalUserId(user.getId())
                    .principalUsername(user.getUsername())
                    .activeOrganizationId(organization.getId())
                    .activeHospitalId(hospital.getId())
                    .permittedOrganizationIds(Set.of(organization.getId()))
                    .permittedHospitalIds(Set.of(hospital.getId()))
                    .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    // ───────────────────────── seed helpers ─────────────────────────

    private Hospital seedHospital(String name) {
        String id = nextId();
        Hospital hospital = hospitalRepository.save(Hospital.builder()
                .name(name)
                .code("FLW" + id)
                .city("Ouagadougou")
                .country("Burkina Faso")
                .address("1 Main St")
                .phoneNumber("+226555" + id)
                .email("flow" + id + "@hospital.test")
                .organization(organization)
                .build());
        cleanup.push(() -> hospitalRepository.deleteById(hospital.getId()));
        return hospital;
    }

    private Role ensureRole(String code, String name) {
        return roleRepository.findByCode(code)
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name(name)
                        .code(code)
                        .description(name + " role")
                        .build()));
    }

    private User seedUser(String prefix) {
        String suffix = nextId();
        User user = userRepository.save(User.builder()
                .username(prefix + suffix)
                .passwordHash("hashed-password")
                .email(prefix + suffix + "@example.test")
                .firstName(prefix + "FN")
                .lastName("User" + suffix)
                .phoneNumber("+22677" + suffix)
                .isActive(true)
                .build());
        cleanup.push(() -> userRepository.deleteById(user.getId()));
        return user;
    }

    private UserRoleHospitalAssignment seedAssignment(User user, Hospital hospital, Role role) {
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
                .assignmentCode("FLW-" + nextId())
                .description(role.getName() + " at " + hospital.getName())
                .user(user)
                .hospital(hospital)
                .role(role)
                .startDate(LocalDate.now())
                .assignedAt(LocalDateTime.now())
                .active(true)
                .build());
        cleanup.push(() -> assignmentRepository.deleteById(assignment.getId()));
        return assignment;
    }

    private void seedStaff(User user, Hospital hospital, UserRoleHospitalAssignment assignment) {
        Staff staff = staffRepository.save(Staff.builder()
                .user(user)
                .hospital(hospital)
                .assignment(assignment)
                .jobTitle(JobTitle.SURGEON)
                .employmentType(EmploymentType.FULL_TIME)
                .licenseNumber("LIC-" + nextId())
                .name("Dr. " + user.getFirstName())
                .active(true)
                .build());
        cleanup.push(() -> staffRepository.deleteById(staff.getId()));
    }

    private Patient seedPatient() {
        User patientUser = seedUser("flowpatient");
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
                .hospitalId(hospitalA.getId())
                .user(patientUser)
                .build());
        cleanup.push(() -> patientRepository.deleteById(saved.getId()));
        PatientHospitalRegistration registration = registrationRepository.save(PatientHospitalRegistration.builder()
                .patient(saved)
                .hospital(hospitalA)
                .mrn("MRN-" + suffix)
                .registrationDate(LocalDate.now())
                .active(true)
                .build());
        cleanup.push(() -> registrationRepository.deleteById(registration.getId()));
        return saved;
    }

    private Pharmacy seedPharmacy(Hospital hospital, String name, PharmacyType type, String phone) {
        Pharmacy pharmacy = pharmacyRepository.save(Pharmacy.builder()
                .hospital(hospital)
                .name(name + " " + nextId())
                .pharmacyType(type)
                .phoneNumber(phone)
                .addressLine1("Rue " + nextId())
                .city("Ouagadougou")
                .active(true)
                .build());
        cleanup.push(() -> pharmacyRepository.deleteById(pharmacy.getId()));
        return pharmacy;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
