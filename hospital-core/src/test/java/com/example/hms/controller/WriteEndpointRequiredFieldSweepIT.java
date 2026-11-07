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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * QA round 29 (D1299 / D1258 / D1282 / D1245 / D1301 / D1312 NOT_FIXED three
 * rounds running): every wave fixed the refusals a source pattern could see,
 * and QA found the rest by calling the endpoints. This guard calls them.
 *
 * <p>Every POST / PUT / PATCH handler with a {@code @RequestBody} is read from
 * Spring's own request-mapping registry, so a new endpoint is swept the day it
 * is added, and driven through MockMvc on a real persistence context as an
 * authorised caller:
 * <ol>
 *   <li><b>Empty body</b> {@code {}}: never a 5xx; never a 2xx unless the
 *       endpoint has no required field ({@link #NO_REQUIRED_FIELD}, each with
 *       why); otherwise a 400 whose {@code fieldErrors} is not empty, refused
 *       at the edge (before any lookup), and every key of which is a property
 *       of the request body (D1317 / D1338: a key the form cannot map).</li>
 *   <li><b>Fill and re-send</b>: every field the first answer named is given a
 *       value of its type and the body re-sent. The answer must not name a
 *       field the body still leaves out: that is a server naming its required
 *       fields one request at a time (D1318, D1331). Never a 5xx.</li>
 *   <li><b>Missing one</b>: from the filled body, each named field is left out
 *       alone. Never a 5xx, never a 2xx (the server called it required).</li>
 *   <li><b>Blank typed</b>: every UUID, enum, date, time and number field sent
 *       {@code ""} at once. Never a 5xx; every such field the empty body named
 *       is named again in ONE answer (D1316: the parser stopped at the first).</li>
 *   <li><b>Malformed typed</b> (QA D1349, round 30): every UUID, enum, date,
 *       time, number and flag field sent a value it cannot read, all at once,
 *       the rest of the body left out. Never a 5xx, never a 2xx; ONE 400 names
 *       every malformed field AND every field the empty body named (the parser
 *       stopped at the first malformed value and bean validation never ran).</li>
 *   <li><b>Blank text</b> (QA D1351, round 30): from the filled body, every
 *       named text field sent {@code "   "} at once. Never a 2xx; every one of
 *       them is named in that answer (a {@code @NotNull String} let a blank
 *       through to a service check that named it one request later).</li>
 * </ol>
 */
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "hms.test.context=write-endpoint-required-field-sweep")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("R29: every write endpoint refuses a missing required field at once, by name, at the edge")
class WriteEndpointRequiredFieldSweepIT extends BaseIT {

    private static final String API = "/api";
    private static final String FIELD_ERRORS = "fieldErrors";
    private static final String NOTES_OPTIONAL = "the notes are optional";
    private static final String SEARCH_FILTER = "a search filter: every criterion is optional";

    /**
     * Endpoints whose body has no required field: an empty body is a valid
     * request (a partial update, an optional note, a filter). Key: "METHOD
     * /pattern". Stale entries fail.
     */
    private static final Map<String, String> NO_REQUIRED_FIELD = Map.ofEntries(
        Map.entry("POST /appointments/search",
            SEARCH_FILTER),
        Map.entry("POST /billing-invoices/search",
            SEARCH_FILTER),
        Map.entry("POST /departments/filter",
            SEARCH_FILTER),
        Map.entry("POST /auth/logout",
            "ends the session; the refresh token in the body is optional"),
        Map.entry("POST /auth/token/refresh",
            "the OAuth refresh exchange: a missing token is a 401 by protocol, no form"),
        Map.entry("POST /cds-services/{serviceId}",
            "CDS Hooks 1.0 protocol endpoint (its client is an EHR, not a form); an unknown service is 404"),
        Map.entry("POST /lab-instrument-outbox/hl7/parse",
            "an HL7 v2 text body, not JSON (415 to a JSON body)"),
        Map.entry("POST /lab/hl7/adapter/inbound",
            "an HL7 v2 text body, not JSON (415 to a JSON body)"),
        Map.entry("POST /webhooks/partner-sms",
            "a partner webhook authenticated by its signature (401 unsigned)"),
        Map.entry("PUT /provider/profile",
            "a raw JSON string body for the provider portal; no fields of its own"),
        Map.entry("POST /empi/candidates",
            "the probabilistic matcher answers 404 while it is switched off; every criterion is optional"),
        Map.entry("PATCH /patients/{id}",
            "a partial demographics patch: a null keeps the stored value"),
        Map.entry("PUT /me/patient/profile",
            "a partial update of the patient's own contact details"),
        Map.entry("PUT /admissions/{admissionId}",
            "a partial edit of a stay: a null keeps the stored value"),
        Map.entry("PUT /imaging/results/{reportId}",
            "a partial edit of a report (its content rule runs on the found report, EmptyClinicalWriteGuardTest)"),
        Map.entry("PUT /lab-reflex-rules/{id}",
            "a partial edit of a rule: a null keeps the stored value"),
        Map.entry("PUT /micro-cultures/{cultureId}",
            "a partial edit of a culture (EmptyClinicalWriteGuardTest KNOWN_GAPS lists its content rule)"),
        Map.entry("PUT /patients/{id}/diagnoses/{diagnosisId}",
            "a partial edit; the change reason is required only with a status or chronic change, named then"),
        Map.entry("PUT /procedure-orders/{orderId}",
            "a partial edit of the author's own order: a null keeps the stored value"),
        Map.entry("PUT /registrations/{id}",
            "a partial edit of a registration: a null keeps the stored value"),
        Map.entry("PUT /staff/{id}",
            "a partial edit of a staff record (not bean-validated on update by design, RequiredRequestIdGuardTest)"),
        Map.entry("PUT /users/{id}",
            "a partial edit of an account: a null keeps the stored value"),
        Map.entry("PUT /super-admin/data-residency/policies/{region}",
            "a partial edit of a region policy: a null keeps the stored value"),
        Map.entry("PUT /platform/organizations/{organizationId}/services/{serviceId}",
            "a partial edit of a platform service link"),
        Map.entry("POST /platform/departments/{departmentId}/services/{serviceId}",
            "links a service; every setting optional (defaults apply)"),
        Map.entry("POST /platform/hospitals/{hospitalId}/services/{serviceId}",
            "links a service; every setting optional (defaults apply)"),
        Map.entry("PUT /departments/{departmentId}/default-sensitivity",
            "a null category clears the department default (documented on SensitivityTagRequestDTO)"),
        Map.entry("PUT /encounters/{encounterId}/sensitivity",
            "a null category clears the visit's explicit tag (documented on SensitivityTagRequestDTO)"),
        Map.entry("POST /patients/{patientId}/record-sharing/opt-out",
            "the patient's own refusal to share; the reason is optional"),
        Map.entry("POST /super-admin/hospitals/{hospitalId}/restore",
            "a restore needs no reason (the suspension and archive carry theirs)"),
        Map.entry("POST /super-admin/organizations/{organizationId}/restore",
            "a restore needs no reason (the suspension and archive carry theirs)"),
        Map.entry("POST /super-admin/hospitals/{hospitalId}/cancel-purge",
            "stops a scheduled purge (keeps data); no reason, the state is checked"),
        Map.entry("POST /super-admin/organizations/{organizationId}/cancel-purge",
            "stops a scheduled purge (keeps data); no reason, the state is checked"),
        Map.entry("POST /super-admin/providers/{providerId}/verify",
            "every field is an optional correction or a cross-check flag"),
        Map.entry("POST /break-glass/{sessionId}/revoke",
            "ends one's own emergency access early; the reason is optional"),
        Map.entry("POST /encounters/{encounterId}/nursing-intake",
            "every section optional; its at-least-one rule runs on the found visit (EmptyClinicalWriteGuardTest CHECKED)"),
        Map.entry("POST /patients/{patientId}/labor/episodes/{episodeId}/entries",
            "every reading optional; its at-least-one rule runs on the found episode (EmptyClinicalWriteGuardTest CHECKED)"),
        Map.entry("POST /patients/{patientId}/postpartum/observations",
            "every reading optional; its at-least-one rule runs on the found delivery (EmptyClinicalWriteGuardTest CHECKED)"),
        Map.entry("POST /patients/{id}/chart-restriction",
            "restricted defaults to false, which lifts the restriction; the reason (to restrict) is named"),
        Map.entry("POST /patients/{patientId}/pro-responses/{responseId}/acknowledge",
            "the action taken is optional"),
        Map.entry("POST /patients/{patientId}/programs/{enrollmentId}/visit",
            "the visit date defaults to today"),
        Map.entry("POST /lab-results/{id}/sign",
            "the signature text and notes are optional (the caller signs)"),
        Map.entry("POST /pharmacy/claims/{id}/accept",
            NOTES_OPTIONAL),
        Map.entry("POST /pharmacy/claims/{id}/pay",
            NOTES_OPTIONAL),
        Map.entry("POST /pharmacy/dispense/{id}/hand-over",
            "the scan value and notes are optional"),
        Map.entry("POST /prescriptions/{id}/pharmacist-verify",
            "the note is optional"),
        Map.entry("POST /prescriptions/{id}/resolve-clarification",
            "the response is optional"),
        Map.entry("POST /prescriptions/{id}/sign",
            "the password / MFA step-up and the allergy override are each required only in their case, named then"),
        Map.entry("POST /reception/insurance/{insuranceId}/attest-eligibility",
            NOTES_OPTIONAL),
        Map.entry("POST /staff/{staffId}/credentials/renew",
            "every field optional (diplomas do not expire: no mandatory expiry)"),
        Map.entry("POST /transfers/{orderId}/complete",
            "the completing staff (the caller by default) and notes are optional"),
        Map.entry("PUT /appointments/{id}/status",
            "the reason is required only for a cancel or a no-show (D1126), named then"),
        Map.entry("PUT /lab-test-definitions/{id}/reference-ranges",
            "a range may leave any bound empty; the ordering rules name the field"),
        Map.entry("PUT /me/patient/education/{resourceId}/progress",
            "every progress field is optional"),
        Map.entry("PUT /nurse/tasks/{taskId}/complete",
            "the completion note is optional"),
        Map.entry("PUT /refills/{refillId}/approve",
            "the provider notes are optional to approve (required to deny or hold, D1315)"),
        Map.entry("PUT /roi-requests/{requestId}/cancel",
            "KNOWN GAP (D1157 class, standing debt): the note is optional on cancel"),
        Map.entry("PUT /roi-requests/{requestId}/fulfil",
            "the note is optional to fulfil")
    );

    /**
     * "METHOD /pattern field" of a required text field a blank value legitimately
     * satisfies (phase 6). Each with why; none so far.
     */
    private static final Set<String> BLANK_TEXT_ACCEPTED = Set.of();

    /**
     * "METHOD /pattern" whose filled-body value refusal (phase 7) still comes
     * after the edge's refusals, each a KNOWN GAP with its defect id. Shrinks
     * only.
     */
    private static final Set<String> VALUE_REFUSED_AFTER_THE_EDGE = Set.of(
        // KNOWN GAP D1371 class (round 31, standing debt in tasklist.md): the first run of phase 7
        // found these 15 besides the handoff (fixed): a value or cross-field rule (an end before its
        // start, the same drug twice, a due date before the invoice date, a QC level, a password
        // check, a recipient list, an event list) checked in the service after bean validation.
        // Each moves to a constraint on its request (a class-level rule for a cross-field one).
        "POST /admin/adt-intake-configs",
        "POST /auth/me/change-email",
        "POST /billing-invoices",
        "POST /drug-interactions",
        "POST /lab-qc-events",
        "POST /me/patient/appointments",
        "POST /mortality/deaths",
        "POST /on-call",
        "POST /reports",
        "POST /session-templates",
        "POST /staff/scheduling/shifts/bulk",
        "POST /super-admin/platform/release-windows",
        "POST /transfusions/administrations/{administrationId}/reaction",
        "POST /webhook-endpoints",
        "PUT /drug-interactions/{id}"
    );

    /**
     * Endpoints whose fill-and-resend answer is a refusal that is not about a
     * field (a state, the caller's role, a sample value the sweep cannot make
     * real), each with why. Stale entries fail. (Sonar S8491, round 31: this
     * comment was left above the previous constant.)
     */
    private static final Map<String, String> NOT_A_FIELD_ONCE_FILLED = Map.ofEntries(
        Map.entry("POST /auth/me/complete-setup", "state: the account has no pending username or password change"),
        Map.entry("POST /high-risk-care-plans", "role: the sweep's caller is not obstetric clinical staff"),
        Map.entry("PUT /high-risk-care-plans/{planId}", "role: the sweep's caller is not obstetric clinical staff"),
        Map.entry("POST /high-risk-care-plans/{planId}/care-team-notes", "role: limited to the care team or the patient"),
        Map.entry("POST /high-risk-care-plans/{planId}/medication-logs", "role: limited to the care team"),
        Map.entry("POST /patients/{patientId}/postpartum/newborn-assessments",
            "state: no delivery recorded in the last 28 days"),
        Map.entry("POST /patients/{patientId}/primary-care", "a looked-up reference (the sample role id) not found"),
        Map.entry("POST /security-policies",
            "the sample code collides with the policy the organization bootstrap seeds (integrity, D1301 standing debt)"),
        Map.entry("POST /super-admin/audit-search/saved", "the sample filter is not JSON; the column is jsonb (integrity)"),
        Map.entry("POST /super-admin/hospitals/{hospitalId}/schedule-purge", "state: only an archived hospital"),
        Map.entry("POST /super-admin/organizations/{organizationId}/schedule-purge", "state: only an archived organization")
    );

    /**
     * Fields required only when another field holds a given value, so the
     * fill-and-resend answer may name them (the filled value made them
     * required). Key: "METHOD /pattern"; each set with when, in the comment.
     */
    private static final Map<String, Set<String>> CONDITIONAL = Map.ofEntries(
        // only for a non-cash method (the sample method is not CASH)
        Map.entry("POST /me/patient/billing/invoices/{invoiceId}/pay", Set.of("transactionReference")),
        // only for an active DNR / POLST (the sample type and status are the first constants)
        Map.entry("POST /advance-directives/patient/{patientId}", Set.of("physicianName", "effectiveDate")),
        // only when the chosen time is not a published free slot (QA D1429, round 34)
        Map.entry("POST /appointments", Set.of("overbookReason"))
    );

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StaffRepository staffRepository;

    private Organization organization;
    private Hospital hospital;
    private RequestPostProcessor staff;
    private RequestPostProcessor patient;
    private final Map<String, String> knownIds = new java.util.HashMap<>();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("every write endpoint: {} refused by name at the edge; required fields named at once; no 5xx")
    void everyWriteEndpointNamesEveryMissingFieldAtOnce() throws Exception {
        seed();
        List<Endpoint> endpoints = endpoints();
        assertThat(endpoints).as("write endpoints with a request body").hasSizeGreaterThan(300);

        Map<String, List<String>> failures = new TreeMap<>();
        Set<String> allowedSeen = new TreeSet<>();
        for (Endpoint endpoint : endpoints) {
            sweep(endpoint, failures, allowedSeen);
        }
        Set<String> stale = new TreeSet<>(NO_REQUIRED_FIELD.keySet());
        NOT_A_FIELD_ONCE_FILLED.keySet().forEach(k -> stale.add("filled " + k));
        stale.removeAll(allowedSeen);

        StringBuilder report = new StringBuilder();
        failures.forEach((category, list) -> {
            report.append("\n== ").append(category).append(" (").append(list.size()).append(")\n");
            list.forEach(line -> report.append("  ").append(line).append('\n'));
        });
        // The full list, for whoever fixes it (the assertion message carries it too).
        java.nio.file.Files.writeString(java.nio.file.Path.of("build", "r29-write-endpoint-sweep.txt"),
            "endpoints=" + endpoints.size() + report);
        assertThat(stale).as("allowlist entries the sweep no longer meets (remove them)").isEmpty();
        assertThat(failures).as("write endpoints that break the required-field contract (QA D1299 class)"
            + report).isEmpty();
    }

    // ------------------------------------------------------------------ sweep

    private void sweep(Endpoint endpoint, Map<String, List<String>> failures, Set<String> allowedSeen)
            throws Exception {
        Answer empty = call(endpoint, endpoint.emptyBody());
        if (!refusedByNameAtTheEdge(endpoint, empty, failures, allowedSeen)
                || Map.class.isAssignableFrom(endpoint.bodyType())) {
            // A free-form map body names its own keys: nothing typed to fill.
            return;
        }
        String key = endpoint.key();
        List<String> unknownKeys = endpoint.unknownKeys(empty.fieldErrors.keySet());
        if (!unknownKeys.isEmpty()) {
            fail(failures, "fieldErrors key that is not a request property", key + " -> " + unknownKeys);
        }
        if (endpoint.elementType() != null && !endpoint.elementType().getName().startsWith("com.example.hms.")) {
            // A list of plain values: the element is the value, there is no field to fill.
            return;
        }
        Map<String, Object> filled = endpoint.filledBody(empty.fieldErrors.keySet());
        Answer second = fillAndResend(endpoint, empty, filled, failures, allowedSeen);
        valueRefusalNamedWithTheEdge(endpoint, empty, filled, second, failures);
        missingOneAtATime(endpoint, filled, failures);
        blankTyped(endpoint, empty, failures);
        malformedTyped(endpoint, empty, failures);
        blankText(endpoint, filled, failures);
    }

    /** Phase 1: {@code {}}. True when it was refused by name and the next phases apply. */
    private static boolean refusedByNameAtTheEdge(Endpoint endpoint, Answer empty, Map<String, List<String>> failures,
                                                  Set<String> allowedSeen) {
        String key = endpoint.key();
        String where = " [" + endpoint.handler.getBeanType().getSimpleName() + "#" + endpoint.handler.getMethod().getName()
            + "(" + endpoint.bodyType().getSimpleName() + ")]";
        boolean listed = NO_REQUIRED_FIELD.containsKey(key);
        if (empty.status >= 500) {
            fail(failures, "5xx on {}", key + " -> " + empty.status + " " + empty.brief());
        } else if (empty.status < 300) {
            if (listed) {
                allowedSeen.add(key);
            } else {
                fail(failures, "2xx on {} (a required field is not declared, or list it in NO_REQUIRED_FIELD)",
                    key + where + " -> " + empty.status);
            }
        } else if (listed) {
            allowedSeen.add(key);
            if (empty.status == 400 && !empty.fieldErrors.isEmpty()) {
                fail(failures, "listed in NO_REQUIRED_FIELD but {} is refused by name",
                    key + " -> " + empty.fieldErrors.keySet());
            }
        } else if (empty.status != 400 || empty.fieldErrors.isEmpty()) {
            fail(failures, "{} not refused by name at the edge (a 400 with fieldErrors)",
                key + where + " -> " + empty.status + " " + empty.brief());
        } else {
            return true;
        }
        return false;
    }

    /** Phase 2: every named field filled; nothing still omitted may be named now. */
    private Answer fillAndResend(Endpoint endpoint, Answer empty, Map<String, Object> filled,
                                 Map<String, List<String>> failures, Set<String> allowedSeen) throws Exception {
        String key = endpoint.key();
        Answer second = call(endpoint, endpoint.wrap(filled));
        if (second.status >= 500) {
            fail(failures, "5xx once the named fields are filled", key + " -> " + second.status + " " + second.brief());
            return second;
        }
        List<String> late = new ArrayList<>();
        for (String named : second.fieldErrors.keySet()) {
            String top = Endpoint.topLevel(named);
            if (!filled.containsKey(top) && !empty.fieldErrors.containsKey(named)
                    && empty.fieldErrors.keySet().stream().noneMatch(k -> Endpoint.topLevel(k).equals(top))) {
                late.add(named);
            }
        }
        late.removeAll(CONDITIONAL.getOrDefault(key, Set.of()));
        if (!late.isEmpty()) {
            fail(failures, "required field named only on a later request (one at a time)",
                key + " -> first " + empty.fieldErrors.keySet() + ", then " + late);
        }
        if (second.status == 400 && second.fieldErrors.isEmpty()) {
            if (NOT_A_FIELD_ONCE_FILLED.containsKey(key)) {
                allowedSeen.add("filled " + key);
            } else {
                fail(failures, "refused without fieldErrors once the named fields are filled",
                    key + " -> " + second.brief());
            }
        }
        return second;
    }

    /**
     * Phase 7 (QA D1371 / D1372, round 31): a value the server refuses once
     * every named field is filled (an unknown handoff type, a reason the
     * service requires) must be named WITH the edge's refusals, not one
     * request after them. From the filled body, each edge-named field is left
     * out alone (an edge refusal); the answer must still name every field the
     * filled body was refused on. A service-only check behind bean validation
     * fails here; declare it on the request (a constraint) so both meet.
     */
    private void valueRefusalNamedWithTheEdge(Endpoint endpoint, Answer empty, Map<String, Object> filled,
                                              Answer second, Map<String, List<String>> failures) throws Exception {
        if (second == null || second.status != 400 || second.fieldErrors.isEmpty()) {
            return;
        }
        Set<String> refusedOnValue = new TreeSet<>();
        for (String named : second.fieldErrors.keySet()) {
            if (filled.containsKey(Endpoint.topLevel(named))) {
                refusedOnValue.add(named);
            }
        }
        if (refusedOnValue.isEmpty()) {
            return;
        }
        String key = endpoint.key();
        for (String edge : new TreeSet<>(empty.fieldErrors.keySet())) {
            String top = Endpoint.topLevel(edge);
            if (refusedOnValue.stream().anyMatch(v -> Endpoint.topLevel(v).equals(top))) {
                continue;
            }
            Map<String, Object> withoutOne = new LinkedHashMap<>(filled);
            withoutOne.remove(top);
            Answer seventh = call(endpoint, endpoint.wrap(withoutOne));
            if (seventh.status != 400 || !seventh.fieldErrors.containsKey(edge)) {
                // Not an edge refusal of that field (the sweep cannot tell which refusal came first).
                continue;
            }
            List<String> unnamed = refusedOnValue.stream().filter(v -> !seventh.fieldErrors.containsKey(v)).toList();
            if (!unnamed.isEmpty() && !VALUE_REFUSED_AFTER_THE_EDGE.contains(key)) {
                fail(failures, "a refused value named only after the edge's refusals (D1371)",
                    key + " without " + top + " -> named " + seventh.fieldErrors.keySet() + ", not " + unnamed);
            }
            return;
        }
    }

    /** Phase 3: each named field left out alone is never accepted. */
    private void missingOneAtATime(Endpoint endpoint, Map<String, Object> filled, Map<String, List<String>> failures)
            throws Exception {
        String key = endpoint.key();
        for (String top : new TreeSet<>(filled.keySet())) {
            Map<String, Object> missingOne = new LinkedHashMap<>(filled);
            missingOne.remove(top);
            Answer third = call(endpoint, endpoint.wrap(missingOne));
            if (third.status >= 500) {
                fail(failures, "5xx with one named field left out", key + " without " + top + " -> " + third.status
                    + " " + third.brief());
            } else if (third.status < 300) {
                fail(failures, "2xx with one named field left out", key + " without " + top);
            }
        }
    }

    /** Phase 4: every typed field sent blank at once; every required one is named in one answer. */
    private void blankTyped(Endpoint endpoint, Answer empty, Map<String, List<String>> failures) throws Exception {
        Map<String, Object> blank = endpoint.blankTypedBody();
        if (blank.isEmpty()) {
            return;
        }
        String key = endpoint.key();
        Answer fourth = call(endpoint, endpoint.wrap(blank));
        if (fourth.status >= 500) {
            fail(failures, "5xx on blank typed fields", key + " -> " + fourth.status + " " + fourth.brief());
            return;
        }
        String prefix = endpoint.elementType() != null ? "[0]." : "";
        List<String> unnamed = new ArrayList<>();
        for (String sent : blank.keySet()) {
            if (empty.fieldErrors.containsKey(prefix + sent) && !fourth.fieldErrors.containsKey(prefix + sent)) {
                unnamed.add(sent);
            }
        }
        if (!unnamed.isEmpty() && fourth.status == 400) {
            fail(failures, "blank typed required field not named (only the first is)",
                key + " -> named " + fourth.fieldErrors.keySet() + ", not " + unnamed);
        }
    }

    /**
     * Phase 5 (QA D1349): every typed field malformed at once; one 400 names each
     * of them and every field the empty body named.
     */
    private void malformedTyped(Endpoint endpoint, Answer empty, Map<String, List<String>> failures)
            throws Exception {
        Map<String, Object> malformed = endpoint.malformedTypedBody();
        if (malformed.isEmpty()) {
            return;
        }
        String key = endpoint.key();
        Answer fifth = call(endpoint, endpoint.wrap(malformed));
        if (fifth.status >= 500) {
            fail(failures, "5xx on malformed typed fields", key + " -> " + fifth.status + " " + fifth.brief());
            return;
        }
        if (fifth.status < 300) {
            fail(failures, "2xx with every typed field malformed", key + " -> " + fifth.status);
            return;
        }
        if (fifth.status != 400) {
            // Refused before the body is read (a 403 / 404 at the door): nothing parsed to name.
            return;
        }
        String prefix = endpoint.elementType() != null ? "[0]." : "";
        List<String> unnamed = new ArrayList<>();
        for (String sent : malformed.keySet()) {
            if (!fifth.fieldErrors.containsKey(prefix + sent)) {
                unnamed.add(sent);
            }
        }
        for (String named : empty.fieldErrors.keySet()) {
            if (!malformed.containsKey(Endpoint.topLevel(named)) && !fifth.fieldErrors.containsKey(named)) {
                unnamed.add(named + " (required)");
            }
        }
        if (!unnamed.isEmpty()) {
            fail(failures, "malformed value or required field not named with the others (D1349)",
                key + " -> named " + fifth.fieldErrors.keySet() + ", not " + unnamed);
        }
    }

    /**
     * Phase 6 (QA D1351): every named text field sent blank at once; each is named
     * in that one answer.
     */
    private void blankText(Endpoint endpoint, Map<String, Object> filled, Map<String, List<String>> failures)
            throws Exception {
        Map<String, Field> props = endpoint.properties();
        Map<String, Object> blank = new LinkedHashMap<>(filled);
        List<String> texts = new ArrayList<>();
        for (String name : filled.keySet()) {
            Field f = props.get(name);
            if (f != null && f.getType() == String.class) {
                blank.put(name, "   ");
                texts.add(name);
            }
        }
        if (texts.isEmpty()) {
            return;
        }
        String key = endpoint.key();
        Answer sixth = call(endpoint, endpoint.wrap(blank));
        if (sixth.status >= 500) {
            fail(failures, "5xx on blank text fields", key + " -> " + sixth.status + " " + sixth.brief());
            return;
        }
        String prefix = endpoint.elementType() != null ? "[0]." : "";
        List<String> unnamed = new ArrayList<>();
        for (String text : texts) {
            if (!sixth.fieldErrors.containsKey(prefix + text)
                    && !BLANK_TEXT_ACCEPTED.contains(key + " " + text)) {
                unnamed.add(text);
            }
        }
        if (!unnamed.isEmpty() && (sixth.status < 300 || sixth.status == 400)) {
            fail(failures, "required text field accepted blank or not named with the others (D1351)",
                key + " -> " + sixth.status + " named " + sixth.fieldErrors.keySet() + ", not " + unnamed);
        }
    }

    private static void fail(Map<String, List<String>> failures, String category, String line) {
        failures.computeIfAbsent(category, k -> new ArrayList<>()).add(line);
    }

    private Answer call(Endpoint endpoint, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(endpoint.method, API + endpoint.url())
            .contextPath(API)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Accept-Language", "en")
            .content(objectMapper.writeValueAsString(body))
            .with(endpoint.patientFacing() ? patient : staff);
        endpoint.headers().forEach(builder::header);
        endpoint.params().forEach(builder::param);
        MockHttpServletResponse response;
        try {
            response = mockMvc.perform(builder).andReturn().getResponse();
        } catch (Exception | AssertionError thrown) {
            return new Answer(599, Map.of(), String.valueOf(thrown.getMessage()));
        } finally {
            SecurityContextHolder.clearContext();
            HospitalContextHolder.clear();
        }
        Map<String, Object> fieldErrors = Map.of();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("{")) {
            try {
                Object errors = objectMapper.readValue(text, Map.class).get(FIELD_ERRORS);
                if (errors instanceof Map<?, ?> map) {
                    Map<String, Object> keys = new LinkedHashMap<>();
                    map.forEach((k, v) -> keys.put(String.valueOf(k), v));
                    fieldErrors = keys;
                }
            } catch (RuntimeException unreadable) {
                // not a JSON object: no fieldErrors
            }
        }
        return new Answer(response.getStatus(), fieldErrors, text);
    }

    private record Answer(int status, Map<String, Object> fieldErrors, String text) {
        String brief() {
            String t = text == null ? "" : text.replaceAll("\\s+", " ");
            return t.length() > 220 ? t.substring(0, 220) + "…" : t;
        }
    }

    // -------------------------------------------------------------- endpoints

    private List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            MethodParameter body = bodyParameter(handler);
            if (body == null || !handler.getBeanType().getName().startsWith("com.example.hms.")) {
                continue;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (method != RequestMethod.POST && method != RequestMethod.PUT && method != RequestMethod.PATCH) {
                    continue;
                }
                for (String pattern : info.getPatternValues()) {
                    out.add(new Endpoint(HttpMethod.valueOf(method.name()), pattern, handler, body, knownIds));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(Endpoint::key));
        return out;
    }

    private static MethodParameter bodyParameter(HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) {
                return parameter;
            }
        }
        return null;
    }

    /** One write endpoint and how to call it. */
    private static final class Endpoint {
        final HttpMethod method;
        final String pattern;
        final HandlerMethod handler;
        final MethodParameter body;
        /** Seeded record ids by lower-case property name (hospitalid, patientid…). */
        final Map<String, String> ids;

        Endpoint(HttpMethod method, String pattern, HandlerMethod handler, MethodParameter body,
                Map<String, String> ids) {
            this.method = method;
            this.pattern = pattern;
            this.handler = handler;
            this.body = body;
            this.ids = ids;
        }

        String key() {
            return method.name() + " " + pattern;
        }

        /** The annotation's name, else its value, else the parameter's own name. */
        private static String nameOf(String name, String value, MethodParameter p) {
            if (!name.isEmpty()) {
                return name;
            }
            return value.isEmpty() ? p.getParameterName() : value;
        }

        boolean patientFacing() {
            return pattern.startsWith("/me/patient") || pattern.startsWith("/me/");
        }

        String url() {
            String url = pattern;
            for (MethodParameter p : handler.getMethodParameters()) {
                PathVariable pv = p.getParameterAnnotation(PathVariable.class);
                if (pv == null) {
                    continue;
                }
                String name = nameOf(pv.name(), pv.value(), p);
                url = url.replaceAll("\\{" + java.util.regex.Pattern.quote(String.valueOf(name)) + "(:[^}]*)?}",
                    java.util.regex.Matcher.quoteReplacement(sample(p.getParameterType(), String.valueOf(name))));
            }
            // Any variable not bound to a parameter (a class-level mapping) gets an id.
            return url.replaceAll("\\{[^}]+}", UUID.randomUUID().toString()).replace("**", "x");
        }

        Map<String, String> params() {
            Map<String, String> out = new LinkedHashMap<>();
            for (MethodParameter p : handler.getMethodParameters()) {
                RequestParam rp = p.getParameterAnnotation(RequestParam.class);
                if (rp == null || !rp.required() || !ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())
                        || Map.class.isAssignableFrom(p.getParameterType())) {
                    continue;
                }
                String name = nameOf(rp.name(), rp.value(), p);
                out.put(name, sample(p.getParameterType(), name));
            }
            return out;
        }

        Map<String, String> headers() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("X-Hospital-Id", ids.get("hospitalid"));
            for (MethodParameter p : handler.getMethodParameters()) {
                RequestHeader rh = p.getParameterAnnotation(RequestHeader.class);
                if (rh == null || !rh.required() || !ValueConstants.DEFAULT_NONE.equals(rh.defaultValue())) {
                    continue;
                }
                String name = nameOf(rh.name(), rh.value(), p);
                out.putIfAbsent(name, UUID.randomUUID().toString());
            }
            return out;
        }

        Class<?> bodyType() {
            return body.getParameterType();
        }

        /** The element type of a {@code List<Dto>} body, or null. */
        Class<?> elementType() {
            if (Collection.class.isAssignableFrom(bodyType())
                    && body.getGenericParameterType() instanceof ParameterizedType p
                    && p.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
            return null;
        }

        /** The body as sent: a list body carries it as its one element. */
        Object wrap(Map<String, Object> body) {
            return elementType() != null ? List.of(body) : body;
        }

        Object emptyBody() {
            return elementType() != null ? List.of(Map.of()) : Map.of();
        }

        /** The JSON properties of the body (of its element, for a list body). */
        Map<String, Field> properties() {
            Class<?> type = elementType() != null ? elementType() : bodyType();
            Map<String, Field> out = new LinkedHashMap<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            || f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                        continue;
                    }
                    out.putIfAbsent(jsonName(f), f);
                }
            }
            return out;
        }

        static String jsonName(Field f) {
            com.fasterxml.jackson.annotation.JsonProperty p =
                f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            if (p != null && !p.value().isEmpty()) {
                return p.value();
            }
            String name = f.getName();
            // Lombok's primitive "boolean isActive" is read and written as "active".
            if (f.getType() == boolean.class && name.length() > 2 && name.startsWith("is")
                    && Character.isUpperCase(name.charAt(2))) {
                return Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            return name;
        }

        static String topLevel(String key) {
            String k = key.startsWith("[") ? key.replaceFirst("^\\[\\d+]\\.?", "") : key;
            int cut = k.length();
            for (char c : new char[] {'.', '['}) {
                int i = k.indexOf(c);
                if (i >= 0 && i < cut) {
                    cut = i;
                }
            }
            return k.substring(0, cut);
        }

        List<String> unknownKeys(Collection<String> keys) {
            Map<String, Field> props = properties();
            List<String> out = new ArrayList<>();
            for (String k : keys) {
                // A list of plain values ([0] of a list of ids) names the element by its index.
                boolean scalarElement = elementType() != null && k.matches("\\[\\d++]")
                    && !elementType().getName().startsWith("com.example.hms.");
                if (!scalarElement && !props.containsKey(topLevel(k))) {
                    out.add(k);
                }
            }
            return out;
        }

        Map<String, Object> filledBody(Collection<String> named) {
            Map<String, Field> props = properties();
            Map<String, Object> out = new LinkedHashMap<>();
            for (String k : named) {
                String javaOrJson = topLevel(k);
                Field f = props.get(javaOrJson);
                if (f == null) {
                    // A key named by the Java property while the JSON name differs: find it by field name.
                    f = props.values().stream().filter(x -> x.getName().equals(javaOrJson)).findFirst().orElse(null);
                }
                if (f != null) {
                    out.putIfAbsent(jsonName(f), sampleValue(f.getType(), f.getGenericType(), f.getName()));
                }
            }
            return out;
        }

        Map<String, Object> blankTypedBody() {
            Map<String, Object> out = new LinkedHashMap<>();
            properties().forEach((name, f) -> {
                if (isTyped(f.getType())) {
                    out.put(name, "");
                }
            });
            return out;
        }

        /** Every typed field (a flag too) with a value it cannot read. */
        Map<String, Object> malformedTypedBody() {
            Map<String, Object> out = new LinkedHashMap<>();
            properties().forEach((name, f) -> {
                Class<?> t = f.getType();
                if (isTyped(t) || t == Boolean.class || t == boolean.class
                        || (t.isPrimitive() && t != char.class)) {
                    out.put(name, "zz-malformed");
                }
            });
            return out;
        }

        private static boolean isTyped(Class<?> t) {
            return t == UUID.class || t.isEnum() || java.time.temporal.Temporal.class.isAssignableFrom(t)
                || Number.class.isAssignableFrom(t);
        }

        private String sample(Class<?> type, String name) {
            Object v = sampleValue(type, type, name);
            return v instanceof Collection<?> c && !c.isEmpty() ? String.valueOf(c.iterator().next()) : String.valueOf(v);
        }

        Object sampleValue(Class<?> type, Type generic, String name) {
            String known = name == null ? null : ids.get(name.toLowerCase(java.util.Locale.ROOT));
            if (known != null && (type == String.class || type == UUID.class)) {
                return known;
            }
            if (type == String.class) {
                return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith("id")
                    ? UUID.randomUUID().toString() : "r29 sweep";
            }
            if (type == UUID.class) {
                return UUID.randomUUID().toString();
            }
            if (type.isEnum()) {
                return ((Enum<?>) type.getEnumConstants()[0]).name();
            }
            if (type == boolean.class || type == Boolean.class) {
                return Boolean.TRUE;
            }
            if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
                return type == BigDecimal.class || type == double.class || type == Double.class ? 1.0 : 1;
            }
            if (type == LocalDate.class) {
                return LocalDate.now().plusDays(1).toString();
            }
            if (type == LocalDateTime.class) {
                return LocalDateTime.now().plusDays(1).withNano(0).toString();
            }
            if (type == LocalTime.class) {
                return "10:00";
            }
            if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
                return OffsetDateTime.now().plusDays(1).withNano(0).toString();
            }
            if (Collection.class.isAssignableFrom(type) || type.isArray()) {
                Class<?> element = type.isArray() ? type.getComponentType() : elementOf(generic);
                return element == null ? List.of() : List.of(sampleValue(element, element, name));
            }
            if (Map.class.isAssignableFrom(type)) {
                return Map.of();
            }
            return Map.of();
        }

        private static Class<?> elementOf(Type generic) {
            if (generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------- seed

    private void seed() {
        organization = organizationRepository.save(Organization.builder()
            .name("R29 Sweep Network").code("R29S-" + nextId()).type(OrganizationType.HOSPITAL_CHAIN).active(true)
            .build());
        hospital = hospitalRepository.save(Hospital.builder()
            .name("R29 Sweep Hospital").code("R29SH" + nextId()).city("Ouagadougou").country("Burkina Faso")
            .address("1 Main St").phoneNumber("+226554" + nextId()).email("r29s" + nextId() + "@hospital.test")
            .organization(organization).build());
        List<String> staffRoles = List.of("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN", "ROLE_DOCTOR",
            "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_ANESTHESIOLOGIST", "ROLE_NURSE", "ROLE_MIDWIFE",
            "ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_CLAIMS_REVIEWER",
            "ROLE_PHARMACIST", "ROLE_PHARMACY_VERIFIER", "ROLE_LAB_TECHNICIAN", "ROLE_LAB_SCIENTIST",
            "ROLE_LAB_MANAGER", "ROLE_LAB_DIRECTOR", "ROLE_RADIOLOGIST", "ROLE_PHYSIOTHERAPIST",
            "ROLE_QUALITY_MANAGER", "ROLE_INVENTORY_CLERK", "ROLE_STORE_MANAGER", "ROLE_STAFF");
        User staffUser = userRepository.save(user("staff"));
        UserRoleHospitalAssignment staffAssignment = assignmentRepository.save(assignment(staffUser, role("ROLE_DOCTOR")));
        Department department = departmentRepository.save(Department.builder()
            .name("R29 Sweep Medecine").code("R29M" + nextId()).email("r29m" + nextId() + "@dept.test")
            .phoneNumber("+226-111-2929").active(true).hospital(hospital).assignment(staffAssignment).build());
        Staff staffMember = staffRepository.save(Staff.builder()
            .user(staffUser).hospital(hospital).assignment(staffAssignment).department(department)
            .jobTitle(JobTitle.values()[0]).employmentType(EmploymentType.FULL_TIME)
            .name("Dr Sweep").active(true).build());
        staff = as(staffUser, staffRoles);

        User patientUser = userRepository.save(user("pat"));
        assignmentRepository.save(assignment(patientUser, role("ROLE_PATIENT")));
        Patient p = patientRepository.save(Patient.builder()
            .firstName("Awa").lastName("Sweep").dateOfBirth(LocalDate.of(1990, 1, 1)).gender("F")
            .address("Secteur 15").city("Ouagadougou").country("Burkina Faso")
            .phoneNumberPrimary("7029" + nextId()).email("r29p" + nextId() + "@patient.test")
            .emergencyContactName("Contact").emergencyContactPhone("71" + nextId())
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(p).hospital(hospital).mrn("CHW29" + nextId())
            .registrationDate(LocalDate.now()).active(true).build());
        patient = as(patientUser, List.of("ROLE_PATIENT"));

        knownIds.put("hospitalid", hospital.getId().toString());
        knownIds.put("organizationid", organization.getId().toString());
        knownIds.put("departmentid", department.getId().toString());
        knownIds.put("staffid", staffMember.getId().toString());
        knownIds.put("userid", staffUser.getId().toString());
        knownIds.put("assignmentid", staffAssignment.getId().toString());
        knownIds.put("patientid", p.getId().toString());
    }

    private Role role(String code) {
        return roleRepository.findByCode(code)
            .orElseGet(() -> roleRepository.save(Role.builder().name(code.substring(5)).code(code)
                .description(code).build()));
    }

    private UserRoleHospitalAssignment assignment(User user, Role role) {
        return UserRoleHospitalAssignment.builder()
            .assignmentCode("R29S-" + nextId()).description("r29").user(user).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build();
    }

    private User user(String prefix) {
        String suffix = nextId();
        return User.builder().username("r29s" + prefix + suffix).passwordHash("hashed")
            .email("r29s" + prefix + suffix + "@example.test").firstName(prefix).lastName("R29" + suffix)
            .phoneNumber("+22679" + suffix).isActive(true).build();
    }

    private RequestPostProcessor as(User caller, List<String> roles) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        CustomUserDetails principal = new CustomUserDetails(caller.getId(), caller.getUsername(),
            caller.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, caller.getPasswordHash(), authorities);
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
