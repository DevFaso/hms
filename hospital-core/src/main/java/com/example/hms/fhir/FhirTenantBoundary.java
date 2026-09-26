package com.example.hms.fhir;

import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one definition of "which FHIR resources may leave the server for this
 * hospital". {@link FhirTenantBoundaryInterceptor} asks it about every
 * resource id a request names and every resource a response carries, so a
 * provider cannot hand out another hospital's row however it queried — and a
 * provider added later is refused everything until its type is taught here.
 *
 * <p>Each rule mirrors the widest legitimate read the providers make today:
 * <ul>
 *   <li>{@code Patient}, and patient-uploaded {@code DocumentReference}
 *       ({@code upl-}): the patient is registered at the hospital — the
 *       {@code $everything} and DocumentReference gate.</li>
 *   <li>{@code Encounter}, {@code Condition}, {@code Immunization},
 *       {@code MedicationRequest}, {@code Appointment}, {@code Slot}, vital
 *       signs ({@code vital-}), micro cultures ({@code micro-}), imaging
 *       reports and orders ({@code imgreport-}, {@code imgorder-}) and
 *       discharge summaries ({@code discharge-}): the row's own
 *       {@code hospital}.</li>
 *   <li>Lab orders and results ({@code laborder-}, {@code labresult-}): the
 *       ordering hospital or the laboratory performing the order —
 *       {@code LabOrder.isHandledBy}, the B1 rule.</li>
 * </ul>
 * An id that does not parse, a prefix that is not listed, and a type that is
 * not listed are all "not visible" — the same answer as a row that exists at
 * another hospital, and as one that does not exist at all.
 *
 * <p>Every query is a literal JPQL string: nothing from the request reaches a
 * query except as a bound parameter.
 */
@Component
public class FhirTenantBoundary {

    static final String UPLOAD_PREFIX = "upl-";
    static final String DISCHARGE_PREFIX = "discharge-";
    static final String LAB_ORDER_PREFIX = "laborder-";
    static final String LAB_RESULT_PREFIX = "labresult-";
    static final String MICRO_PREFIX = "micro-";
    static final String IMAGING_REPORT_PREFIX = "imgreport-";
    static final String IMAGING_ORDER_PREFIX = "imgorder-";
    static final String VITAL_PREFIX = "vital-";

    /**
     * Every resource type this boundary can vouch for. A provider whose type
     * is missing here has every read and search refused (fail closed), and
     * {@code FhirTenantBoundaryIT} fails the build first.
     */
    public static final Set<String> KNOWN_TYPES = Set.of(
        "Patient", "Encounter", "Condition", "Immunization", "MedicationRequest", "Appointment", "Slot",
        "Observation", "DiagnosticReport", "ServiceRequest", "DocumentReference");

    /**
     * Role codes (bare, as stored on an assignment) that may READ through FHIR
     * at the hospital a request is bound to: the chart readers of
     * {@code EncounterController.ENCOUNTER_LIST_ROLES}, with PHYSICIAN and
     * SURGEON named because nothing expands an assignment code. A super-admin
     * is global and needs none.
     */
    static final Set<String> READ_ROLE_CODES = Set.of(
        "DOCTOR", "PHYSICIAN", "SURGEON", "NURSE", "MIDWIFE",
        "RADIOLOGIST", "ANESTHESIOLOGIST", "PHYSIOTHERAPIST");

    /** Every other method (the flag-gated writes): the charting clinicians, not the consulting ones. */
    static final Set<String> WRITE_ROLE_CODES = Set.of("DOCTOR", "PHYSICIAN", "SURGEON", "NURSE", "MIDWIFE");

    /** {@code $export}: the hospital admin its service admits (a super-admin is global). */
    static final Set<String> EXPORT_ROLE_CODES = Set.of("HOSPITAL_ADMIN");

    /** Keycloak's per-hospital roles: {@code "<ROLE>@<hospital-uuid>"} entries (KeycloakHospitalContextResolver). */
    static final String CLAIM_ROLE_ASSIGNMENTS = "role_assignments";
    private static final String ROLE_PREFIX = "ROLE_";

    private static final int UUID_LENGTH = 36;
    private static final String IDS = "ids";
    private static final String HOSPITAL = "hospitalId";

    private static final String PATIENTS = "SELECT DISTINCT r.patient.id FROM PatientHospitalRegistration r "
        + "WHERE r.patient.id IN :ids AND r.hospital.id = :hospitalId";
    private static final String ENCOUNTERS =
        "SELECT e.id FROM Encounter e WHERE e.id IN :ids AND e.hospital.id = :hospitalId";
    private static final String PROBLEMS =
        "SELECT p.id FROM PatientProblem p WHERE p.id IN :ids AND p.hospital.id = :hospitalId";
    private static final String IMMUNIZATIONS =
        "SELECT i.id FROM PatientImmunization i WHERE i.id IN :ids AND i.hospital.id = :hospitalId";
    private static final String PRESCRIPTIONS =
        "SELECT p.id FROM Prescription p WHERE p.id IN :ids AND p.hospital.id = :hospitalId";
    private static final String APPOINTMENTS =
        "SELECT a.id FROM Appointment a WHERE a.id IN :ids AND a.hospital.id = :hospitalId";
    private static final String SLOTS =
        "SELECT s.id FROM AppointmentSlot s WHERE s.id IN :ids AND s.hospital.id = :hospitalId";
    private static final String VITALS =
        "SELECT v.id FROM PatientVitalSign v WHERE v.id IN :ids AND v.hospital.id = :hospitalId";
    // LEFT JOIN, not a path: o.performingHospital.id in the WHERE clause would
    // be an inner join and drop every order with no performing laboratory.
    private static final String LAB_ORDERS = "SELECT o.id FROM LabOrder o LEFT JOIN o.performingHospital ph "
        + "WHERE o.id IN :ids AND (o.hospital.id = :hospitalId OR ph.id = :hospitalId)";
    private static final String LAB_RESULTS = "SELECT r.id FROM LabResult r JOIN r.labOrder o "
        + "LEFT JOIN o.performingHospital ph "
        + "WHERE r.id IN :ids AND (o.hospital.id = :hospitalId OR ph.id = :hospitalId)";
    private static final String MICRO_CULTURES =
        "SELECT c.id FROM MicroCultureResult c WHERE c.id IN :ids AND c.hospital.id = :hospitalId";
    private static final String IMAGING_REPORTS =
        "SELECT r.id FROM ImagingReport r WHERE r.id IN :ids AND r.hospital.id = :hospitalId";
    private static final String IMAGING_ORDERS =
        "SELECT o.id FROM ImagingOrder o WHERE o.id IN :ids AND o.hospital.id = :hospitalId";
    private static final String DISCHARGE_SUMMARIES =
        "SELECT s.id FROM DischargeSummary s WHERE s.id IN :ids AND s.hospital.id = :hospitalId";
    private static final String UPLOADED_DOCUMENTS = "SELECT d.id FROM PatientUploadedDocument d "
        + "WHERE d.id IN :ids AND EXISTS (SELECT r.id FROM PatientHospitalRegistration r "
        + "WHERE r.patient = d.patient AND r.hospital.id = :hospitalId)";

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    public FhirTenantBoundary(UserRoleHospitalAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * The hospital a FHIR request is bounded to, taken from the authenticated
     * principal — never from the request alone. {@code X-Hospital-Id} only
     * SELECTS among the hospitals the principal already holds:
     * {@code HospitalContextRequestOverrides} accepts the header for any
     * hospital when the principal has no permitted hospital at all, so a
     * header-chosen hospital is honoured here only when it is one of the
     * principal's own, or when the principal is a super-admin (who must pin
     * one — {@link HospitalContext#pinnedHospitalId()} is null in global view).
     *
     * @return the bound hospital, or {@code null} when the request has none
     */
    static UUID boundHospital(HospitalContext context) {
        if (context == null) {
            return null;
        }
        UUID pinned = context.pinnedHospitalId();
        if (pinned == null) {
            return null;
        }
        if (context.isSuperAdmin()) {
            return pinned;
        }
        return context.getPermittedHospitalIds().contains(pinned) ? pinned : null;
    }

    /**
     * Does the principal hold one of {@code roleCodes} AT {@code hospitalId}?
     *
     * <p>Spring Security's authorities are the union of the caller's roles at
     * every hospital, so a DOCTOR at A who is a RECEPTIONIST at B passes any
     * path matcher while acting at B. This asks about the one hospital the
     * request is bound to, from the principal:
     * <ul>
     *   <li>a super-admin is global: true;</li>
     *   <li>a Keycloak token: its {@code role_assignments} claim, the
     *       {@code ROLE@hospital} pairs the realm issued;</li>
     *   <li>an HMS token: the LIVE active assignments of the user at that
     *       hospital, so a role revoked after sign-in stops counting at once.</li>
     * </ul>
     * Codes compare bare and upper-case: {@code ROLE_DOCTOR} and {@code DOCTOR}
     * are the same role, as {@code RoleValidator} treats them.
     */
    @Transactional(readOnly = true)
    public boolean holdsRoleAt(HospitalContext context, Authentication authentication, UUID hospitalId,
                               Set<String> roleCodes) {
        if (context == null || hospitalId == null) {
            return false;
        }
        if (context.isSuperAdmin()) {
            return true;
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object claim = jwt.getToken().getClaim(CLAIM_ROLE_ASSIGNMENTS);
            // Set.of(...).contains(null) throws: an entry for another hospital maps to null.
            return claim instanceof Collection<?> entries && entries.stream()
                .map(entry -> roleAt(entry, hospitalId))
                .anyMatch(role -> role != null && roleCodes.contains(role));
        }
        UUID userId = context.getPrincipalUserId();
        if (userId == null) {
            return false;
        }
        Set<String> stored = new HashSet<>();
        for (String code : roleCodes) {
            stored.add(code);
            stored.add(ROLE_PREFIX + code);
        }
        return assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(userId, hospitalId, stored);
    }

    /** The bare role of a {@code "ROLE@hospital"} entry naming {@code hospitalId}, else {@code null}. */
    private static String roleAt(Object entry, UUID hospitalId) {
        if (entry == null) {
            return null;
        }
        String value = entry.toString().trim();
        int at = value.lastIndexOf('@');
        if (at <= 0 || !hospitalId.toString().equalsIgnoreCase(value.substring(at + 1).trim())) {
            return null;
        }
        String role = value.substring(0, at).trim().toUpperCase(Locale.ROOT);
        return role.startsWith(ROLE_PREFIX) ? role.substring(ROLE_PREFIX.length()) : role;
    }

    /** Is the resource {@code resourceType/idPart} visible at {@code hospitalId}? */
    @Transactional(readOnly = true)
    public boolean isVisible(String resourceType, String idPart, UUID hospitalId) {
        return idPart != null && visibleIdParts(resourceType, List.of(idPart), hospitalId).contains(idPart);
    }

    /**
     * The subset of {@code idParts} (FHIR logical ids of one resource type)
     * that are visible at {@code hospitalId}. One query per id namespace.
     */
    @Transactional(readOnly = true)
    public Set<String> visibleIdParts(String resourceType, Collection<String> idParts, UUID hospitalId) {
        Set<String> visible = new HashSet<>();
        if (resourceType == null || hospitalId == null || idParts == null || idParts.isEmpty()) {
            return visible;
        }
        switch (resourceType) {
            case "Patient" -> collect(visible, idParts, "", PATIENTS, hospitalId);
            case "Encounter" -> collect(visible, idParts, "", ENCOUNTERS, hospitalId);
            case "Condition" -> collect(visible, idParts, "", PROBLEMS, hospitalId);
            case "Immunization" -> collect(visible, idParts, "", IMMUNIZATIONS, hospitalId);
            case "MedicationRequest" -> collect(visible, idParts, "", PRESCRIPTIONS, hospitalId);
            case "Appointment" -> collect(visible, idParts, "", APPOINTMENTS, hospitalId);
            case "Slot" -> collect(visible, idParts, "", SLOTS, hospitalId);
            case "Observation" -> {
                collect(visible, idParts, LAB_RESULT_PREFIX, LAB_RESULTS, hospitalId);
                collectVitals(visible, idParts, hospitalId);
            }
            case "DiagnosticReport" -> {
                collect(visible, idParts, LAB_ORDER_PREFIX, LAB_ORDERS, hospitalId);
                collect(visible, idParts, MICRO_PREFIX, MICRO_CULTURES, hospitalId);
                collect(visible, idParts, IMAGING_REPORT_PREFIX, IMAGING_REPORTS, hospitalId);
            }
            case "ServiceRequest" -> {
                collect(visible, idParts, LAB_ORDER_PREFIX, LAB_ORDERS, hospitalId);
                collect(visible, idParts, IMAGING_ORDER_PREFIX, IMAGING_ORDERS, hospitalId);
            }
            case "DocumentReference" -> {
                collect(visible, idParts, UPLOAD_PREFIX, UPLOADED_DOCUMENTS, hospitalId);
                collect(visible, idParts, DISCHARGE_PREFIX, DISCHARGE_SUMMARIES, hospitalId);
            }
            default -> {
                // Not taught here: nothing of this type leaves the server.
            }
        }
        return visible;
    }

    /**
     * Ids of the form {@code <prefix><uuid>} (an empty prefix for a bare
     * UUID): the ones whose row the query returns are added to
     * {@code visible}.
     */
    private void collect(Set<String> visible, Collection<String> idParts, String prefix, String jpql,
                         UUID hospitalId) {
        Map<UUID, List<String>> byRow = new HashMap<>();
        for (String idPart : idParts) {
            if (idPart == null || !idPart.startsWith(prefix)) {
                continue;
            }
            UUID row = parse(idPart.substring(prefix.length()));
            if (row != null) {
                byRow.computeIfAbsent(row, k -> new ArrayList<>()).add(idPart);
            }
        }
        for (UUID row : query(jpql, byRow.keySet(), hospitalId)) {
            visible.addAll(byRow.getOrDefault(row, List.of()));
        }
    }

    /**
     * {@code vital-<uuid>-<component>}: one vital-signs row expands to several
     * Observations, so the row id is the fixed-width UUID after the prefix.
     */
    private void collectVitals(Set<String> visible, Collection<String> idParts, UUID hospitalId) {
        Map<UUID, List<String>> byRow = new HashMap<>();
        for (String idPart : idParts) {
            if (idPart == null || !idPart.startsWith(VITAL_PREFIX)
                || idPart.length() <= VITAL_PREFIX.length() + UUID_LENGTH + 1
                || idPart.charAt(VITAL_PREFIX.length() + UUID_LENGTH) != '-') {
                continue;
            }
            UUID row = parse(idPart.substring(VITAL_PREFIX.length(), VITAL_PREFIX.length() + UUID_LENGTH));
            if (row != null) {
                byRow.computeIfAbsent(row, k -> new ArrayList<>()).add(idPart);
            }
        }
        for (UUID row : query(VITALS, byRow.keySet(), hospitalId)) {
            visible.addAll(byRow.getOrDefault(row, List.of()));
        }
    }

    private List<UUID> query(String jpql, Set<UUID> rows, UUID hospitalId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery(jpql, UUID.class)
            .setParameter(IDS, rows)
            .setParameter(HOSPITAL, hospitalId)
            .getResultList();
    }

    private static UUID parse(String raw) {
        // UUID.fromString accepts non-canonical forms ("1-1-1-1-1"); a FHIR id
        // this server minted is always the canonical 36 characters.
        if (raw == null || raw.length() != UUID_LENGTH) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
