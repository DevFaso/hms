package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1870 (round 53): from hospital B, {@code GET /patients/{id}/allergies} and {@code /diagnoses}
 * for a patient registered at A only answered 400 "Le patient n'est pas enregistré dans l'hôpital
 * indiqué", while {@code GET /patients/{id}} answered 404. The distinct answer told a caller holding
 * the id that the patient exists at another hospital.
 *
 * <p>Every refusal for "this patient is not registered here" answers 404 {@code patient.notFound},
 * exactly as an unknown id does. Two shapes are scanned in production code:
 * <ul>
 *   <li>a negated registration check ({@code !…isRegisteredInHospital(…)},
 *       {@code !…isPatientRegisteredInHospital…(…)}, {@code !…existsByPatientIdAndHospitalId(…)})
 *       whose block throws something other than {@code ResourceNotFoundException};</li>
 *   <li>a refusal whose message key says the patient is not registered ({@code …notRegistered…}).</li>
 * </ul>
 * A site that keeps a distinct answer is listed below with why it is not an existence oracle.
 */
@DisplayName("D1870: a patient not registered at the caller's hospital is 404, never a distinct refusal")
class PatientNotHereIsNotFoundGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    static final Pattern NEGATED_REGISTRATION_CHECK = Pattern.compile(
        "if\\s*+\\(\\s*+!\\s*+[^{;]*?(?:isRegisteredInHospital|isPatientRegisteredInHospital\\w*+"
            + "|existsByPatientIdAndHospitalId\\w*+|existsByPatient_IdAndHospital_Id\\w*+)\\s*+\\([^{;]*\\)\\s*+\\{"
            + "([^}]*+)\\}");

    static final Pattern NOT_REGISTERED_REFUSAL = Pattern.compile(
        "throw\\s++new\\s++(?:[\\w.]*\\.)?(\\w*Exception)\\s*+\\([^;]*?\"[\\w.]*?[nN]otRegistered[\\w.]*\"");

    private static final String PICKED = "QA D1870 audit (round 54): the patient is picked in the request body, "
        + "not addressed by a patient URL; the field error names the choice to correct - ";

    private static final String OWN = "QA D1870 audit (round 54): the patient's own portal request; the caller is the "
        + "patient, so naming the hospital they chose reveals nothing about anyone else";

    /** {@code Class#method} that keeps a distinct answer. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "IntakeOutputServiceImpl#recordEntry", 1,
            "PatientChartAccess.require has already opened the chart (or answered 404); this refuses a WRITE "
                + "without an active registration, so it reveals nothing the caller cannot already read",
        "NurseTaskServiceImpl#createHandoff", 1, PICKED + "the handoff's patient",
        "NurseTaskServiceImpl#createNursingTask", 1, PICKED + "the task's patient",
        "PatientRecallServiceImpl#createRecall", 1, PICKED + "the recall's patient",
        "SlotInventoryServiceImpl#book", 1, PICKED + "the slot is booked for a patient the desk chose",
        "LabOrderServiceImpl#buildLabOrder", 1, PICKED + "the order's patient",
        "NursingNoteServiceImpl#ensurePatientRegistration", 1,
            PICKED + "a new note's patient; an addendum's note was already found at this hospital",
        "HighRiskPregnancyCarePlanServiceImpl#ensurePatientBelongsToHospital", 1,
            PICKED + "a new plan's patient; an update's plan was already found at this hospital",
        "PrenatalSchedulingServiceImpl#ensurePatientBelongsToHospital", 1, PICKED + "the schedule's patient",
        "PatientPortalServiceImpl#refuseDeactivatedRegistration", 1, OWN,
        "PatientPortalServiceImpl#requireBookingRegistration", 1, OWN,
        "PatientPortalServiceImpl#writeHospitalFor", 1, OWN);

    @Test
    @DisplayName("every 'not registered here' refusal answers 404, or says why it is not an existence oracle")
    void notRegisteredHereIsNotFound() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        int checks = 0;
        for (Path file : javaFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String owner = file.getFileName().toString().replace(".java", "");
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            Matcher check = NEGATED_REGISTRATION_CHECK.matcher(source);
            while (check.find()) {
                checks++;
                Matcher thrown = Pattern.compile("throw\\s++new\\s++([\\w.]++)").matcher(check.group(1));
                if (thrown.find() && !thrown.group(1).endsWith("ResourceNotFoundException")) {
                    int at = check.start(1) + thrown.start();
                    seen.add(at);
                    actual.merge(owner + "#" + methodName(AppointmentSlotRulesGuardTest.enclosingMethod(source, at)),
                        1, Integer::sum);
                }
            }
            Matcher refusal = NOT_REGISTERED_REFUSAL.matcher(source);
            while (refusal.find()) {
                if (seen.contains(refusal.start()) || refusal.group(1).equals("ResourceNotFoundException")) {
                    continue;
                }
                actual.merge(owner + "#" + methodName(AppointmentSlotRulesGuardTest.enclosingMethod(source,
                    refusal.start())), 1, Integer::sum);
            }
        }
        assertThat(checks).as("the scan found the registration checks").isGreaterThan(10);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a refusal that tells the caller the patient exists at another hospital (QA D1870): throw "
                + "ResourceNotFoundException(\"patient.notFound\", patientId), as GET /patients/{id} does"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed or removed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1870 shape and accepts its fix")
    void catchesTheD1870Shape() {
        String before = """
                if (!registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)) {
                    throw new BusinessException("patient.notRegisteredInHospital");
                }
            """;
        Matcher m = NEGATED_REGISTRATION_CHECK.matcher(before);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).contains("BusinessException");
        assertThat(NOT_REGISTERED_REFUSAL.matcher(before).find()).isTrue();
        String after = before.replace("new BusinessException(\"patient.notRegisteredInHospital\")",
            "new ResourceNotFoundException(\"patient.notFound\", patientId)");
        Matcher fixed = NEGATED_REGISTRATION_CHECK.matcher(after);
        assertThat(fixed.find()).isTrue();
        assertThat(fixed.group(1)).contains("ResourceNotFoundException");
        assertThat(NOT_REGISTERED_REFUSAL.matcher(after).find()).isFalse();
    }

    private static String methodName(String body) {
        int paren = body.indexOf('(');
        String head = body.substring(0, paren).trim();
        return head.substring(head.lastIndexOf(' ') + 1);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.startsWith(MAIN.resolve("repository")) && !p.startsWith(MAIN.resolve("model")))
                .sorted()
                .toList();
        }
    }
}
