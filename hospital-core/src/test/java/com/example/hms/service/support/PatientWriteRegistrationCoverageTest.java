package com.example.hms.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D401 architecture guard: every service WRITE that takes a patient id must
 * check that the patient is registered at the hospital the row is written in.
 *
 * <p>A public method named like a write ({@code create*}, {@code book*},
 * {@code update*}, {@code administer*}, ...) that takes a {@code UUID patientId}
 * or a request DTO declaring one is a "patient write". EACH such method must
 * reach {@link PatientRegistrationGuard}, or one of the established checks in
 * {@link #REGISTRATION_CHECKS}, in its own body or in a same-class helper it
 * calls, or be recorded ({@link #RECORDED_METHODS} per method, {@link #RECORDED}
 * per class) with the reason it is safe. Both maps only shrink.
 */
class PatientWriteRegistrationCoverageTest {

    private static final Path SERVICE_ROOT = Path.of("src/main/java/com/example/hms/service");
    private static final Path PAYLOAD_ROOT = Path.of("src/main/java/com/example/hms");

    /**
     * Calls that prove a registration (or equivalent reach) check in a write method. Each is matched
     * as a CALL on code with comments and string literals blanked out, so a commented-out guard, or
     * the name in a log message, is not coverage (QA #917 verification G1).
     */
    private static final List<Pattern> REGISTRATION_CHECKS = Stream.of(
            "patientRegistrationGuard\\s*+\\.\\s*+requireRegisteredAt",
            "existsByPatientIdAndHospitalId",
            "isRegisteredInHospital",
            "requirePatientInReach",
            "ensurePatientRegistration",
            "ensurePatientBelongsToHospital",
            "validatePatientHospitalContext",
            "PatientChartAccess\\s*+\\.\\s*+\\w++",
            "patientChartAccess\\s*+\\.\\s*+\\w++",
            "ensurePatientRegistered",
            // hand-rolled registration checks in the non-Impl services (enrollment, panel, ROI, PRO)
            "requirePatientInTenant",
            "requireInTenant")
        .map(call -> Pattern.compile("\\b" + call + "\\s*+\\("))
        .toList();

    private static final String BOUND_TO_PARENT =
        "the patient is bound to an in-scope parent row (the caller's dispense, prescription, order or encounter)"
            + " and must equal that row's patient";

    private static final String UPDATE_OF_OWNED_ROW =
        "updates a row loaded under the acting hospital's ownership check; the patient on the row is never"
            + " reassigned from the request";

    private static final String PATIENT_LEVEL_INSURANCE =
        "insurance is held by the patient, not written into a hospital (the row carries no hospital)";

    /** Simple class name -> why none of its writes needs the check. Frozen: it only shrinks. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("AuditEventLogServiceImpl", "writes audit rows about a patient, not into a chart"),
        entry("InBasketServiceImpl", "internal staff notifications raised by other services, never from a request body"),
        entry("DischargeSummaryServiceImpl", BOUND_TO_PARENT
            + " (an encounter owned by the acting hospital, D-r5 tenancy sweep)"),
        entry("LabResultServiceImpl", BOUND_TO_PARENT + " (the lab order, whose creation already checks registration)"),
        entry("DispenseServiceImpl", BOUND_TO_PARENT + " (the prescription at the acting pharmacy)"),
        entry("PharmacyClaimServiceImpl", BOUND_TO_PARENT + " (the dispense)"),
        entry("PharmacyPaymentServiceImpl", BOUND_TO_PARENT + " (the dispense)"),
        entry("PharmacySaleServiceImpl", "over-the-counter sale: the patient is optional and resolved through the"
            + " registration-scoped PatientRepository.findById"),
        entry("RecordSharingOptOutServiceImpl", "the opt-out is a patient-level consent row (no hospital); requirePatient"
            + " loads through the tenant-scoped finder, assigned hospital AND registration"));

    /** {@code Class#method} -> why that one write needs no check of its own. Frozen: it only shrinks. */
    private static final Map<String, String> RECORDED_METHODS = Map.ofEntries(
        entry("BillingInvoiceServiceImpl#recordPayment", BOUND_TO_PARENT + " (the invoice, locked and matched to the patient)"),
        entry("BillingInvoiceServiceImpl#recordPatientPayment", BOUND_TO_PARENT
            + " (the invoice, locked and matched to the patient)"),
        entry("CdsAcknowledgementServiceImpl#acknowledge", "requirePatientAccessible demands the patient's own"
            + " hospital equal the resolved scope, stricter than a registration there"),
        entry("PanelService#end", BOUND_TO_PARENT + " (the assignment, loaded by id + patient + hospital)"),
        entry("PatientGuarantorServiceImpl#deactivate", BOUND_TO_PARENT
            + " (the guarantor, loaded by id + patient + hospital)"),
        entry("PatientGuarantorServiceImpl#reactivate", BOUND_TO_PARENT
            + " (the guarantor, loaded by id + patient + hospital)"),
        entry("ProgramEnrollmentService#recordVisit", BOUND_TO_PARENT
            + " (the enrolment, loaded by id + patient + hospital)"),
        entry("ProgramEnrollmentService#updateStatus", BOUND_TO_PARENT
            + " (the enrolment, loaded by id + patient + hospital)"),
        entry("BirthPlanServiceImpl#updateBirthPlan", UPDATE_OF_OWNED_ROW + " (getBirthPlanInReach)"),
        entry("FamilyHistoryServiceImpl#updateFamilyHistory", UPDATE_OF_OWNED_ROW),
        entry("ImmunizationServiceImpl#updateImmunization", UPDATE_OF_OWNED_ROW),
        entry("LaborServiceImpl#addEntry", BOUND_TO_PARENT + " (the labor episode, loaded by id + hospital + patient)"),
        entry("LaborServiceImpl#recordDelivery", BOUND_TO_PARENT + " (the labor episode, loaded by id + hospital + patient)"),
        entry("MaternalHistoryServiceImpl#updateMaternalHistory", UPDATE_OF_OWNED_ROW
            + " (a request naming another patient is refused)"),
        entry("MedicationHistoryServiceImpl#updatePharmacyFill", UPDATE_OF_OWNED_ROW),
        entry("MtmReviewServiceImpl#updateReview", UPDATE_OF_OWNED_ROW),
        entry("PatientEducationServiceImpl#updateVisitDocumentation", UPDATE_OF_OWNED_ROW),
        entry("PatientGuarantorServiceImpl#update", BOUND_TO_PARENT + " (the guarantor, loaded by id + patient + hospital)"),
        entry("PatientHospitalRegistrationServiceImpl#updateRegistration",
            "edits the registration row itself, owned by the acting hospital"),
        entry("PatientInsuranceServiceImpl#addInsuranceToPatient",
            PATIENT_LEVEL_INSURANCE),
        entry("PatientInsuranceServiceImpl#updatePatientInsurance", PATIENT_LEVEL_INSURANCE),
        entry("SocialHistoryServiceImpl#updateSocialHistory", UPDATE_OF_OWNED_ROW),
        entry("UltrasoundServiceImpl#updateOrder", UPDATE_OF_OWNED_ROW + " (getOrderInScope)"));

    private static final Pattern WRITE_METHOD = Pattern.compile(
        "public\\s++[\\w<>\\[\\],.?]++(?:\\s*+[\\w<>\\[\\],.?]++)*?\\s++((?:create|book|add|record|submit|place|order|admit|register|save|upload"
            + "|log|start|schedule|update|document|administer|refer|sign|request|issue|amend|attach|assign"
            + "|transfer|discharge|complete|initiate|capture|import|dispense|prescribe|track|enroll|revoke"
            + "|acknowledge|deactivate|reactivate|end|optOut)\\w*+)\\s*+\\(([^)]*+)\\)");
    /** A private method declaration: the same-class helpers a write may delegate its check to. */
    private static final Pattern ANY_METHOD = Pattern.compile(
        "private\\s++(?:static\\s++)?(?:final\\s++)?[\\w<>\\[\\],.?]++(?:\\s*+<[^>]*+>)?\\s++(\\w++)\\s*+\\(([^)]*+)\\)"
            + "\\s*+(?:throws\\s++[\\w.,\\s]++)?\\{");
    /** An unqualified call ({@code helper(..)}, not {@code repo.helper(..)}): only those reach a same-class helper. */
    private static final Pattern CALL = Pattern.compile("(?<![.\\w])(\\w++)\\s*+\\(");
    private static final Pattern SERVICE_ANNOTATION = Pattern.compile("(?m)^@Service\\b");
    private static final Pattern PATIENT_ID_FIELD = Pattern.compile("\\bUUID\\s+patientId\\b");
    private static final Pattern PATIENT_ID_PARAM = Pattern.compile("UUID\\s+patientId\\b");

    @Test
    @DisplayName("every write method taking a patient id checks the registration at the acting hospital")
    void everyPatientWriteChecksRegistration() throws IOException {
        Set<String> dtosWithPatientId = dtosDeclaringPatientId();
        Set<String> unchecked = new TreeSet<>();
        Set<String> patientWriteClasses = new TreeSet<>();

        for (Path file : serviceClasses()) {
            String name = className(file);
            Map<String, Boolean> writes = patientWrites(Files.readString(file, StandardCharsets.UTF_8), dtosWithPatientId);
            if (writes.isEmpty()) {
                continue;
            }
            patientWriteClasses.add(name);
            if (RECORDED.containsKey(name)) {
                continue;
            }
            writes.forEach((method, checked) -> {
                if (!checked && !RECORDED_METHODS.containsKey(name + "#" + method)) {
                    unchecked.add(name + "#" + method);
                }
            });
        }

        assertThat(patientWriteClasses)
            .as("the scan must find the D401 write paths, or it is scanning nothing")
            .contains("AppointmentServiceImpl", "PrescriptionServiceImpl", "EncounterServiceImpl",
                // services without the Impl suffix, and writes named track/enroll/acknowledge (G2)
                "ProgramEnrollmentService", "PanelService", "RoiRequestService", "ProResponseService",
                "PatientEducationServiceImpl");
        assertThat(unchecked)
            .as("patient writes whose body (or a same-class helper it calls) has no registration check at the"
                + " acting hospital (QA D401): use PatientRegistrationGuard.requireRegisteredAt, or record"
                + " Class#method with its reason")
            .isEmpty();
    }

    @Test
    @DisplayName("the recorded exceptions are still patient writes without the check (the maps only shrink)")
    void recordedEntriesAreStillNeeded() throws IOException {
        Set<String> dtosWithPatientId = dtosDeclaringPatientId();
        Set<String> staleClasses = new TreeSet<>(RECORDED.keySet());
        Set<String> staleMethods = new TreeSet<>(RECORDED_METHODS.keySet());
        for (Path file : serviceClasses()) {
            String name = className(file);
            Map<String, Boolean> writes =
                patientWrites(Files.readString(file, StandardCharsets.UTF_8), dtosWithPatientId);
            if (writes.containsValue(Boolean.FALSE)) {
                staleClasses.remove(name);
            }
            writes.forEach((method, checked) -> {
                if (!checked) {
                    staleMethods.remove(name + "#" + method);
                }
            });
        }
        assertThat(staleClasses).as("recorded classes that no longer need the exception: remove them").isEmpty();
        assertThat(staleMethods).as("recorded methods that no longer need the exception: remove them").isEmpty();
    }

    @Test
    @DisplayName("the scan is per method: a check in one write does not cover a sibling write")
    void aCheckInOneMethodDoesNotCoverASibling() {
        String source = """
            class FooServiceImpl {
                public Foo createFoo(UUID patientId) { patientRegistrationGuard.requireRegisteredAt(patientId, h); return null; }
                public Foo updateFoo(UUID patientId) { return repo.save(patientId); } // the caller's write
                public Foo createBar(UUID patientId) { return null; }
                private void ignored() { /* the patient's chart */ patientRegistrationGuard.toString(); }
                public Foo signFoo(UUID patientId) { return helper(patientId); }
                private Foo helper(UUID patientId) { if (x) { requirePatientInReach(patientId); } return null; }
            }
            """;
        assertThat(patientWrites(source, Set.of()))
            .containsEntry("createFoo", true)
            .containsEntry("updateFoo", false)
            .containsEntry("createBar", false)
            .containsEntry("signFoo", true);
    }

    @Test
    @DisplayName("a commented-out guard, or one named only in a comment or string, is not a check (G1)")
    void aCommentedOutGuardIsUnchecked() {
        String source = """
            class FooServiceImpl {
                public Foo createFoo(UUID patientId) {
                    //patientRegistrationGuard.requireRegisteredAt(patientId, h);
                    return null;
                }
                public Foo bookFoo(UUID patientId) { /* patientRegistrationGuard.requireRegisteredAt(patientId, h); */ return null; }
                public Foo addFoo(UUID patientId) { log.info("patientRegistrationGuard.requireRegisteredAt(x)"); return null; }
                public Foo signFoo(UUID patientId) { patientRegistrationGuard.toString(); return null; }
                public Foo trackFoo(UUID patientId) { patientRegistrationGuard . requireRegisteredAt (patientId, h); return null; }
                public Foo enrollFoo(UUID patientId) { return null; }
            }
            """;
        assertThat(patientWrites(source, Set.of()))
            .containsEntry("createFoo", false)
            .containsEntry("bookFoo", false)
            .containsEntry("addFoo", false)
            .containsEntry("signFoo", false)
            .containsEntry("trackFoo", true)
            .containsEntry("enrollFoo", false);
    }

    /** Every Spring service under service/, with or without the Impl suffix (G2). */
    private static List<Path> serviceClasses() throws IOException {
        try (Stream<Path> files = Files.walk(SERVICE_ROOT)) {
            List<Path> found = new ArrayList<>();
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (p.toString().endsWith("Impl.java") || SERVICE_ANNOTATION.matcher(text).find()) {
                    found.add(p);
                }
            }
            return found;
        }
    }

    private static String className(Path file) {
        return file.getFileName().toString().replace(".java", "");
    }

    /** Write method name -> whether its body, or a same-class helper it reaches, carries a check. */
    private static Map<String, Boolean> patientWrites(String rawSource, Set<String> dtosWithPatientId) {
        String source = withoutCommentsAndLiterals(rawSource);
        Map<String, List<String>> bodies = methodBodies(source);
        Map<String, Boolean> writes = new TreeMap<>();
        Matcher m = WRITE_METHOD.matcher(source);
        while (m.find()) {
            String params = m.group(2);
            boolean takesPatient = PATIENT_ID_PARAM.matcher(params).find()
                || dtosWithPatientId.stream().anyMatch(dto -> Pattern.compile("\\b" + dto + "\\b").matcher(params).find());
            int open = source.indexOf('{', m.end());
            int semi = source.indexOf(';', m.end());
            if (!takesPatient || open < 0 || (semi >= 0 && semi < open)) {
                continue;
            }
            String body = source.substring(open, closingBrace(source, open) + 1);
            writes.merge(m.group(1), reachesCheck(body, bodies), Boolean::logicalAnd);
        }
        return writes;
    }

    private static boolean reachesCheck(String body, Map<String, List<String>> bodies) {
        Set<String> seen = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(body);
        while (!pending.isEmpty()) {
            String next = pending.poll();
            if (REGISTRATION_CHECKS.stream().anyMatch(check -> check.matcher(next).find())) {
                return true;
            }
            Matcher call = CALL.matcher(next);
            while (call.find()) {
                String callee = call.group(1);
                if (bodies.containsKey(callee) && seen.add(callee)) {
                    pending.addAll(bodies.get(callee));
                }
            }
        }
        return false;
    }

    private static Map<String, List<String>> methodBodies(String source) {
        Map<String, List<String>> bodies = new TreeMap<>();
        Matcher m = ANY_METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            bodies.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                .add(source.substring(open, closingBrace(source, open) + 1));
        }
        return bodies;
    }

    /** Index of the brace closing the one at {@code open}, skipping comments and string/char literals. */
    private static int closingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int eol = source.indexOf('\n', i);
                i = eol < 0 ? source.length() : eol;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 1;
            } else if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length() - 1;
    }

    /**
     * The source with every comment and string/char literal replaced by spaces (newlines kept), so a
     * check that is commented out or only named in a message never counts as a call (G1).
     */
    static String withoutCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            int end;
            if (source.startsWith("//", i)) {
                int eol = source.indexOf('\n', i);
                end = eol < 0 ? source.length() : eol;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                end = close < 0 ? source.length() : close + 2;
            } else if (c == '"' || c == '\'') {
                end = Math.min(endOfLiteral(source, i, c) + 1, source.length());
            } else {
                out.append(c);
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                out.append(source.charAt(k) == '\n' ? '\n' : ' ');
            }
            i = end;
        }
        return out.toString();
    }

    private static int endOfLiteral(String source, int start, char quote) {
        if (quote == '"' && source.startsWith("\"\"\"", start)) {
            int end = source.indexOf("\"\"\"", start + 3);
            return end < 0 ? source.length() : end + 2;
        }
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return source.length();
    }

    private static Set<String> dtosDeclaringPatientId() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(PAYLOAD_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")
                && (p.toString().contains("payload") || p.toString().contains("dto"))).toList()) {
                if (PATIENT_ID_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    names.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        return names;
    }
}
