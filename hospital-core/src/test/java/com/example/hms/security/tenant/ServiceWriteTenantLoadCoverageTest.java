package com.example.hms.security.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 5, D262/D263: a hospital-B doctor discharged, edited and cancelled
 * hospital A's admission because the write loaded it with a plain
 * {@code findById}. {@code TenantAwareJpaRepository.findById} only filters
 * entities that implement {@link TenantScoped}; Admission, like most
 * hospital-owned rows, does not, so the plain load returned another
 * hospital's row and nothing on the write path refused it.
 *
 * <p>This ratchet scans every service method that is not a public read
 * ({@code get…}, {@code find…}, {@code list…}, …) and that loads by id
 * ({@code findById}, {@code findByIdUnscoped}, {@code existsById},
 * {@code deleteById}) through a repository of a hospital-owned, non
 * {@code TenantScoped} entity (or a patient through {@code findByIdUnscoped}).
 * The method must apply a tenant check in its own body —
 * {@link TenantOwnership}, the resolver, {@code requireActiveHospitalId}, a
 * registration check, a comparison of the row's hospital, or one of the
 * house helpers listed in {@link #TENANT_CHECKS} — or be recorded below with
 * its reason. Private methods are scanned whatever their name: a private
 * loader is where a write's load usually happens.
 *
 * <p>The map only shrinks: a new unchecked load fails the build, and an entry
 * that no longer matches must be deleted in the same change. Source scan, in
 * the style of {@code ActingScopeCoverageTest}.
 */
class ServiceWriteTenantLoadCoverageTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern PUBLIC_READ = Pattern.compile(
        "^(get|find|list|search|count|is|has|to|map|build|export|preview|compute|calculate|summar|view|"
            + "fetch|read|describe|render|format)");

    /** What counts as a tenant check inside the method body. */
    private static final Pattern TENANT_CHECKS = Pattern.compile(String.join("|", List.of(
        "requireActiveHospitalId", "requirePinned", "pinnedHospitalId", "narrowTo\\(", "enforceHospitalScope",
        "TenantOwnership", "loadTenantScoped", "ScopeResolver", "currentScope\\(", "recordAccessPolicy",
        "existsByPatientIdAndHospitalId", "requireActingAt", "requireAppointmentVisible", "InScope\\(",
        "Scoped\\(", "InTenant\\(", "InActingHospital", "AtActingHospital", "validateArtifactScope",
        "validateStaffHospitalMatch", "requireConsultantAtHospital", "requireOrderingHospital",
        "requireResultInActiveHospital", "ensurePatientRegistered", "isRegisteredInHospital",
        "requirePatientOwnership", "assertCanManage", "assertHasAnyRoleAt", "authorizeForExisting",
        "hasAnyRoleAtHospital", "validateHospitalMatch", "isHandledBy", "validateHospitalScope",
        "findByIdAndHospital_Id", "requireHospitalScope\\(",
        "(getHospital\\(\\)\\.getId\\(\\)|getHospitalId\\(\\))\\)?\\s*\\.equals\\(",
        "\\.equals\\([^;]*getHospital", "Objects\\.equals\\([^;]*[Hh]ospital")));

    private static final Pattern SIGNATURE = Pattern.compile("^\\s*(public|private|protected)[^=;(]*\\s(\\w+)\\s*\\([^;]*$");
    /**
     * QA D602: a named by-id loader ({@code findDetailedById}, ...) is a by-id load
     * too; the shift PUT loaded through one and nothing saw it. Excluded are the
     * re-reads of a row already loaded under a check (a lock, a status re-read)
     * and {@code findAllById} of actors.
     */
    private static final Pattern BY_ID_CALL = Pattern.compile("\\b(\\w+)\\.(findById|findByIdUnscoped|existsById|deleteById"
        + "|find(?!Locked|WithLock|Status|All)\\w*ById)\\(");
    private static final Pattern REPOSITORY_FIELD = Pattern.compile("private\\s+(?:final\\s+)?(\\w+Repository)\\s+(\\w+)\\s*;");
    private static final Pattern REPOSITORY_ENTITY = Pattern.compile("extends\\s+\\w*Repository<\\s*(\\w+)\\s*,");
    private static final Pattern HOSPITAL_FIELD = Pattern.compile("private (Hospital hospital|UUID hospitalId);");

    private static final String ACTOR = "loads the named staff member or assignment (an actor, not the row written);"
        + " the calling write holds it to the target row's hospital";
    private static final String PATIENT = "resolves the patient only; every write caller then checks the patient's"
        + " registration at the acting hospital (or ownership, for a patient)";
    private static final String CALLER_CHECKS = "a private loader; each calling write applies the hospital check on the"
        + " returned row (see the caller)";
    private static final String SYSTEM = "a scheduled or system job with no request scope; it acts on rows already"
        + " written under their own hospital";
    private static final String OWN_ROW = "bounded by ownership of the caller's own row";
    private static final String SUPER_ADMIN = "super-admin only (class-level @PreAuthorize)";
    private static final String SHARED = "a catalog, pharmacy-setup or reference row read to label or price; no other"
        + " hospital's clinical record is written";
    private static final String UNSWEPT = "not yet swept: tracked in tasklist.md Standing platform debt"
        + " (QA r5 tenancy guard residue)";

    /** Class.method#call → why the method needs no tenant check of its own. Frozen: it only shrinks. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("AdmissionOrderSetServiceImpl.deactivate#findById", ACTOR + "; the order set itself is loaded scoped"),
        entry("AppointmentServiceImpl.resolvePatient#findByIdUnscoped", PATIENT),
        entry("AppointmentServiceImpl.resolveStaff#findById", ACTOR),
        entry("AuditEventLogServiceImpl.resolveAssignment#findById", "the caller's own assignment, checked against the caller"),
        entry("BillingInvoiceServiceImpl.creditCheckInCopays#findById", CALLER_CHECKS + " (invoice writes load through loadInScope)"),
        entry("BillingInvoiceServiceImpl.recomputeAndPersistTotals#findById", CALLER_CHECKS + " (invoice item writes check writableInvoice first)"),
        entry("BirthPlanServiceImpl.getBirthPlanByIdOrThrow#findById", CALLER_CHECKS + " (requireAtActingHospital)"),
        entry("BreakGlassServiceImpl.declare#findByIdUnscoped", "QA D409: break-the-glass exists for a patient the"
            + " acting hospital does not know; the caller is checked as privileged at the hospital, and the"
            + " declaration is audited and, after an opt-out, notified to the patient"),
        entry("CareGapTraceService.traceOne#findById", SYSTEM),
        entry("ChatMessageServiceImpl.sendMessage#findById", "resolves the message recipient; no clinical row is written"),
        entry("ConsultationServiceImpl.getConsultationEntity#findById", CALLER_CHECKS + " (getConsultationInScope)"),
        entry("ConsultationServiceImpl.resolveStaff#findById", ACTOR),
        entry("DepartmentServiceImpl.findDepartmentOrThrow#findById", CALLER_CHECKS + " (enforceHospitalScopeOnEntity)"),
        entry("DispenseServiceImpl.resolveCatalogItem#findById", SHARED),
        entry("EncounterServiceImpl.resolveAuthorStaff#findById", ACTOR),
        entry("HighRiskPregnancyCarePlanServiceImpl.findPlanOrThrow#findById", CALLER_CHECKS + " (findPlanInActingHospital, or ownership for a patient)"),
        entry("InBasketServiceImpl.findItemOwnedBy#findById", OWN_ROW + " (the in-basket item's recipient)"),
        entry("InBasketServiceImpl.resolveEncounter#findById", UNSWEPT),
        entry("InventoryServiceImpl.resolveMedication#findById", SHARED),
        entry("InventoryServiceImpl.resolvePharmacy#findById", SHARED),
        entry("LabOrderServiceImpl.namedAssignment#findById", ACTOR),
        entry("LabReflexRuleServiceImpl.loadTestDef#findById", SHARED),
        entry("LegacyAllergyTextBackfillWorker.backfillOne#findByIdUnscoped", SYSTEM),
        entry("MllpAllowedSenderServiceImpl.deactivate#findById", SUPER_ADMIN),
        entry("MllpAllowedSenderServiceImpl.update#findById", SUPER_ADMIN),
        entry("MllpInboundAdtServiceImpl.processAdt#findByIdUnscoped", "HL7 inbound: bound to the sender's receiving hospital, not a user scope"),
        entry("ObstetricAlertCurrency.newbornResolved#findById", SYSTEM + " (re-reads the clinical record that raised the escalation row, by the id the row stored; read-only, inside the sweep)"),
        entry("ObstetricAlertCurrency.partographResolved#findById", SYSTEM + " (re-reads the clinical record that raised the escalation row, by the id the row stored; read-only, inside the sweep)"),
        entry("ObstetricAlertCurrency.postpartumResolved#findById", SYSTEM + " (re-reads the clinical record that raised the escalation row, by the id the row stored; read-only, inside the sweep)"),
        entry("ObstetricAlertEscalationWorker.escalateOne#findByIdUnscoped", SYSTEM + " (QA D659: the patient the escalation row was raised for, to name her in the reminder; the reminder goes only to that row's hospital staff)"),
        entry("ObstetricAlertEscalationWorker.escalateOne#findById", SYSTEM + " (the escalation row carries its own hospital; no user scope exists in a sweep)"),
        entry("PatientChartAccess.requireOwnRecord#findByIdUnscoped", OWN_ROW + " (the patient's own chart)"),
        entry("PatientPortalServiceImpl.verifyProxyAccess#findByIdUnscoped", "QA D1212 (round 23): the grantor's"
            + " record, read only after the caller's active, unexpired, in-scope proxy grant FROM that grantor is verified;"
            + " the grant is patient-owned, not a hospital's, so the proxy's tenancy is not the bound"),
        entry("PatientEducationServiceImpl.incrementResourceViewCount#findById", SHARED),
        entry("PatientEducationServiceImpl.submitQuestion#findById", UNSWEPT),
        entry("PatientEducationServiceImpl.trackProgress#findById", UNSWEPT),
        entry("PatientEducationServiceImpl.updateResourceAverageRating#findById", SHARED),
        entry("PatientPortalServiceImpl.requestMedicationRefill#findById", OWN_ROW + " (the patient's own prescription)"),
        entry("PatientServiceImpl.fetchPatient#findByIdUnscoped", PATIENT),
        entry("PatientVitalSignServiceImpl.resolveAssignment#findById", ACTOR),
        entry("PrescriptionCopyPdfService.copyForPatient#findById", OWN_ROW
            + " (QA D966: a read; 404 unless the prescription is the calling patient's own)"),
        entry("PatientVitalSignServiceImpl.resolveRegistration#findById", UNSWEPT),
        entry("PermissionServiceImpl.createPermission#findById", ACTOR),
        entry("PharmacySaleServiceImpl.buildLine#findById", SHARED),
        entry("PhoneVerificationServiceImpl.consumeVerifiedChallenge#findById", OWN_ROW + " (the caller's own challenge)"),
        entry("PlatformRegistryServiceImpl.linkDepartmentToService#findById", UNSWEPT),
        entry("PostpartumCareServiceImpl.resolveRegistration#findById", UNSWEPT),
        entry("PrescriberPharmacyNotificationWriter.writeMessage#findById", SYSTEM),
        entry("PrescriptionServiceImpl.findPrescriptionOrNotFound#findById", CALLER_CHECKS + " (isAtHospital / requireActiveHospitalId)"),
        entry("PrescriptionServiceImpl.resolveEncounterContext#findById", UNSWEPT),
        entry("PrescriptionServiceImpl.resolvePatient#findByIdUnscoped", PATIENT),
        entry("PrescriptionServiceImpl.resolveStaffContext#findById", ACTOR),
        entry("PrescriptionSmsDispatchServiceImpl.dispatch#findById", SYSTEM),
        entry("RecordAccessPolicyImpl.unlessRestricted#findByIdUnscoped", "the access policy itself decides reach"),
        entry("RecordSharingOptOutServiceImpl.requirePatient#findByIdUnscoped", PATIENT),
        entry("ReferralExpiryPersistence.tryExpire#findById", SYSTEM),
        entry("SignableReportLocator.owningHospitalId#findById", "resolves the owning hospital FOR the caller's check"),
        entry("StaffSchedulingServiceImpl.findStaff#findById", ACTOR),
        entry("StaffServiceImpl.findStaffOrThrow#findById", CALLER_CHECKS + " (requireHospitalScope)"),
        entry("SuperAdminLabOrderServiceImpl.tryResolveLabTestDefinitionById#findById", SUPER_ADMIN),
        entry("TreatmentPlanServiceImpl.fetchAssignment#findById", ACTOR),
        entry("TreatmentPlanServiceImpl.fetchPatient#findByIdUnscoped", PATIENT),
        entry("TreatmentPlanServiceImpl.fetchStaff#findById", ACTOR),
        entry("TreatmentPlanServiceImpl.resolveEncounter#findById", CALLER_CHECKS + " (validateEncounterContext)"),
        entry("UserRoleHospitalAssignmentServiceImpl.deleteAssignment#deleteById", "assignment administration; findChangeable applies its own change rules"),
        entry("UserRoleHospitalAssignmentServiceImpl.findAllChangeable#findById", "assignment administration with its own change rules"),
        entry("UserRoleHospitalAssignmentServiceImpl.findChangeable#findById", "assignment administration with its own change rules"),
        entry("UserRoleHospitalAssignmentServiceImpl.sendNotifications#findById", "notifies about an assignment just written"));

    @Test
    @DisplayName("every service write that loads a hospital-owned row by id checks the tenant, or is recorded")
    void everyByIdWriteLoadIsTenantChecked() throws IOException {
        Set<String> found = scan();
        TreeSet<String> unrecorded = new TreeSet<>(found);
        unrecorded.removeAll(RECORDED.keySet());
        assertThat(unrecorded).as("a service write loads a hospital-owned row by id without a tenant check:"
            + " use TenantOwnership.requireOwnedByActingHospital (404, not 403) after the load,"
            + " or record why it needs none").isEmpty();
        TreeSet<String> stale = new TreeSet<>(RECORDED.keySet());
        stale.removeAll(found);
        assertThat(stale).as("recorded entries that no longer match: remove them, the list only shrinks").isEmpty();
    }

    @Test
    @DisplayName("every recorded entry carries a reason")
    void everyEntryIsReasoned() {
        RECORDED.forEach((key, reason) -> assertThat(reason).as(key).isNotBlank());
    }

    @Test
    @DisplayName("the scanner sees the D262 shape and accepts the fixed one")
    void scannerSeesTheD262Shape() {
        Set<String> repos = Set.of("AdmissionRepository");
        Map<String, String> fields = Map.of("admissionRepository", "AdmissionRepository");
        String unchecked = """
                public AdmissionResponseDTO dischargePatient(UUID admissionId, Request request) {
                    Admission admission = admissionRepository.findById(admissionId)
                        .orElseThrow();
                    admission.discharge();
                }
            """;
        String checked = """
                public AdmissionResponseDTO dischargePatient(UUID admissionId, Request request) {
                    Admission admission = admissionRepository.findById(admissionId)
                        .orElseThrow();
                    TenantOwnership.requireOwnedByActingHospital(admission.getHospital(), "k", admissionId);
                }
            """;
        assertThat(scanSource("AdmissionServiceImpl", unchecked, fields, repos))
            .containsExactly("AdmissionServiceImpl.dischargePatient#findById");
        assertThat(scanSource("AdmissionServiceImpl", checked, fields, repos)).isEmpty();
        assertThat(scanSource("AdmissionServiceImpl", unchecked.replace("dischargePatient", "getAdmission"), fields, repos))
            .as("a public read is out of scope").isEmpty();
    }

    @Test
    @DisplayName("QA D602: the scanner sees a named by-id loader (the shift PUT shape) and accepts the scoped one")
    void scannerSeesTheD602Shape() {
        Set<String> repos = Set.of("StaffShiftRepository");
        Map<String, String> fields = Map.of("shiftRepository", "StaffShiftRepository");
        String unchecked = """
                private StaffShift loadShift(UUID id, Locale locale) {
                    return shiftRepository.findDetailedById(id).orElseThrow();
                }
            """;
        String checked = """
                private StaffShift loadShift(UUID id, Locale locale) {
                    return shiftRepository.findDetailedById(id)
                        .filter(shift -> hospitalInScope(shift.getHospital()))
                        .orElseThrow();
                }
            """;
        assertThat(scanSource("StaffSchedulingServiceImpl", unchecked, fields, repos))
            .containsExactly("StaffSchedulingServiceImpl.loadShift#findDetailedById");
        assertThat(scanSource("StaffSchedulingServiceImpl", checked, fields, repos)).isEmpty();
    }

    private static Set<String> scan() throws IOException {
        Set<String> entities = new TreeSet<>();
        for (Path model : javaFiles(MAIN.resolve("model"))) {
            String source = read(model);
            if (HOSPITAL_FIELD.matcher(source).find() && !source.contains("TenantScoped")) {
                entities.add(simpleName(model));
            }
        }
        Set<String> repositories = new TreeSet<>();
        for (Path repository : javaFiles(MAIN.resolve("repository"))) {
            Matcher matcher = REPOSITORY_ENTITY.matcher(read(repository));
            if (matcher.find() && entities.contains(matcher.group(1))) {
                repositories.add(simpleName(repository));
            }
        }
        repositories.add("PatientRepository");
        Set<String> found = new TreeSet<>();
        for (Path service : javaFiles(MAIN.resolve("service"))) {
            String source = read(service);
            Map<String, String> fields = new HashMap<>();
            Matcher field = REPOSITORY_FIELD.matcher(source);
            while (field.find()) {
                fields.put(field.group(2), field.group(1));
            }
            found.addAll(scanSource(simpleName(service), source, fields, repositories));
        }
        return found;
    }

    /** The scan of one class: field name → repository type, and the repositories in scope. */
    private static Set<String> scanSource(String className, String source, Map<String, String> fields,
                                          Set<String> repositories) {
        Set<String> found = new TreeSet<>();
        String[] lines = source.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher signature = SIGNATURE.matcher(lines[i]);
            if (!signature.find()) {
                i++;
                continue;
            }
            boolean isPrivate = "private".equals(signature.group(1));
            String name = signature.group(2);
            int depth = 0;
            boolean started = false;
            int j = i;
            StringBuilder body = new StringBuilder();
            while (j < lines.length) {
                body.append(lines[j]).append('\n');
                depth += count(lines[j], '{') - count(lines[j], '}');
                if (lines[j].indexOf('{') >= 0) {
                    started = true;
                }
                if (started && depth <= 0) {
                    break;
                }
                if (!started && lines[j].stripTrailing().endsWith(";")) {
                    break;
                }
                j++;
            }
            i = j + 1;
            String text = body.toString();
            if ((PUBLIC_READ.matcher(name).find() && !isPrivate) || TENANT_CHECKS.matcher(text).find()) {
                continue;
            }
            Matcher call = BY_ID_CALL.matcher(text);
            while (call.find()) {
                String repository = fields.get(call.group(1));
                if (repository == null || !repositories.contains(repository)) {
                    continue;
                }
                if ("PatientRepository".equals(repository) && !"findByIdUnscoped".equals(call.group(2))) {
                    continue; // Patient is TenantScoped: its findById is filtered by registration
                }
                found.add(className + "." + name + "#" + call.group(2));
            }
        }
        return found;
    }

    private static int count(String line, char c) {
        int n = 0;
        for (int k = 0; k < line.length(); k++) {
            if (line.charAt(k) == c) {
                n++;
            }
        }
        return n;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String simpleName(Path path) {
        String file = path.getFileName().toString();
        return file.substring(0, file.length() - ".java".length());
    }
}
