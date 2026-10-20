package com.example.hms.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>A service implementation is a "patient write" when one of its public
 * methods named like a write ({@code create*}, {@code book*}, {@code record*},
 * {@code submit*}, ...) takes a {@code UUID patientId} or a request DTO that
 * declares one. Such a class must go through {@link PatientRegistrationGuard},
 * or one of the established registration checks listed in
 * {@link #REGISTRATION_CHECKS}, or be recorded in {@link #RECORDED} with the
 * reason it is safe. The map only shrinks: a new write service that skips the
 * check fails here until it is fixed (or deliberately recorded).
 */
class PatientWriteRegistrationCoverageTest {

    private static final Path SERVICE_ROOT = Path.of("src/main/java/com/example/hms/service");
    private static final Path PAYLOAD_ROOT = Path.of("src/main/java/com/example/hms");

    /** Tokens that prove a registration (or equivalent reach) check in the class. */
    private static final List<String> REGISTRATION_CHECKS = List.of(
        "patientRegistrationGuard",
        "existsByPatientIdAndHospitalId",
        "isRegisteredInHospital",
        "requirePatientInReach",
        "ensurePatientRegistration",
        "ensurePatientBelongsToHospital",
        "validatePatientHospitalContext",
        "PatientChartAccess");

    private static final String BOUND_TO_PARENT =
        "the patient is bound to an in-scope parent row (the caller's dispense, prescription, order or encounter)"
            + " and must equal that row's patient";

    /** Simple class name -> why it is not a patient write that needs the check. Frozen: it only shrinks. */
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
            + " registration-scoped PatientRepository.findById"));

    private static final Pattern WRITE_METHOD = Pattern.compile(
        "public\\s+[\\w<>\\[\\],.? ]+\\s+((?:create|book|add|record|submit|place|order|admit|register|save|upload|log|start|schedule)\\w*)\\s*\\(([^)]*)\\)");
    private static final Pattern PATIENT_ID_FIELD = Pattern.compile("\\bUUID\\s+patientId\\b");
    private static final Pattern PATIENT_ID_PARAM = Pattern.compile("UUID\\s+patientId\\b");

    @Test
    @DisplayName("every write service taking a patient id checks the registration at the acting hospital")
    void everyPatientWriteChecksRegistration() throws IOException {
        Set<String> dtosWithPatientId = dtosDeclaringPatientId();
        Map<String, Set<String>> unchecked = new TreeMap<>();
        Set<String> patientWriteClasses = new TreeSet<>();

        try (Stream<Path> files = Files.walk(SERVICE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Impl.java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString().replace(".java", "");
                Set<String> writes = patientWrites(source, dtosWithPatientId);
                if (writes.isEmpty()) {
                    continue;
                }
                patientWriteClasses.add(name);
                boolean checked = REGISTRATION_CHECKS.stream().anyMatch(source::contains);
                if (!checked && !RECORDED.containsKey(name)) {
                    unchecked.put(name, writes);
                }
            }
        }

        assertThat(patientWriteClasses)
            .as("the scan must find the D401 write paths, or it is scanning nothing")
            .contains("AppointmentServiceImpl", "PrescriptionServiceImpl", "EncounterServiceImpl");
        assertThat(unchecked)
            .as("patient writes with no registration check at the acting hospital (QA D401): use"
                + " PatientRegistrationGuard.requireRegisteredAt, or record the class with its reason")
            .isEmpty();
    }

    @Test
    @DisplayName("the recorded exceptions are still patient writes without the check (the map only shrinks)")
    void recordedEntriesAreStillNeeded() throws IOException {
        Set<String> dtosWithPatientId = dtosDeclaringPatientId();
        Set<String> stale = new TreeSet<>(RECORDED.keySet());
        try (Stream<Path> files = Files.walk(SERVICE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Impl.java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (!RECORDED.containsKey(name)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean checked = REGISTRATION_CHECKS.stream().anyMatch(source::contains);
                if (!patientWrites(source, dtosWithPatientId).isEmpty() && !checked) {
                    stale.remove(name);
                }
            }
        }
        assertThat(stale).as("recorded classes that no longer need the exception: remove them").isEmpty();
    }

    private static Set<String> patientWrites(String source, Set<String> dtosWithPatientId) {
        Set<String> writes = new TreeSet<>();
        Matcher m = WRITE_METHOD.matcher(source);
        while (m.find()) {
            String params = m.group(2);
            boolean takesPatient = PATIENT_ID_PARAM.matcher(params).find()
                || dtosWithPatientId.stream().anyMatch(dto -> Pattern.compile("\\b" + dto + "\\b").matcher(params).find());
            if (takesPatient) {
                writes.add(m.group(1));
            }
        }
        return writes;
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
