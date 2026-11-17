package com.example.hms.architecture;

import com.example.hms.model.PatientNotificationDelivery;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K1/K3 (V312): patient appointment and result notices go through the delivery ledger,
 * which retries, shows failures to the hospital's administrators and never sends twice, and they
 * carry no clinical content. Before it, the appointment reminder sent its SMS inline and a failure
 * was one log line; a released result reached only the in-app bell. Each rule below reads the
 * source, so a new booking path, a new release path or a new inline patient SMS fails here:
 * <ul>
 *   <li>every service that books an appointment queues its confirmation ({@code confirmBooking});</li>
 *   <li>every path that releases a result to the patient queues the matching result event;</li>
 *   <li>a direct {@code smsService.send(} is allowed only in the frozen list below, which only
 *       shrinks: a new patient notice uses the ledger;</li>
 *   <li>the result templates take the hospital and nothing else, in every language;</li>
 *   <li>the ledger row stores no message, number or address.</li>
 * </ul>
 */
@DisplayName("Patient notifications go through the delivery ledger (K1/K3)")
class PatientNotificationRoutingGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final Path MAIN = Path.of("src/main/java");
    private static final Path BUNDLES = Path.of("src/main/resources/i18n");

    private static final Pattern BOOKS_APPOINTMENT = Pattern.compile(
        "new\\s+(?:com\\.example\\.hms\\.model\\.)?Appointment\\(\\)|\\b(?:com\\.example\\.hms\\.model\\.)?Appointment\\.builder\\(\\)");
    private static final Pattern DIRECT_SMS = Pattern.compile("\\b[sS]msService(?:\\(\\))?\\.send\\(");

    /**
     * The direct SMS senders that existed when the ledger landed: one-time codes, staff and
     * pharmacy texts, clinician pages. Only shrinks. {@code PatientNotificationDispatchService} is
     * the ledger's own sender.
     */
    private static final Set<String> DIRECT_SMS_SENDERS = Set.of(
        "PatientNotificationDispatchService.java",
        "CriticalValueNotificationService.java",
        "ImagingCriticalNotificationService.java",
        "PasswordResetServiceImpl.java",
        "UserRoleHospitalAssignmentServiceImpl.java",
        "UsernameReminderServiceImpl.java",
        "PrescriptionSmsDispatchServiceImpl.java",
        "PharmacyServiceSupport.java",
        "ProScreeningEscalationService.java",
        // Waitlist offers and recall notices: patient outreach still sent inline (standing debt,
        // tracked in the V312 PR body); removing it from here is the fix.
        "PatientOutreachNotifier.java");

    @Test
    @DisplayName("every service that books an appointment queues its confirmation")
    void everyBookingIsConfirmed() throws IOException {
        List<String> offenders = new ArrayList<>();
        int found = 0;
        for (Path file : javaFiles(SERVICES)) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (BOOKS_APPOINTMENT.matcher(source).find()) {
                found++;
                if (!source.contains("confirmBooking(")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(found).as("appointment-booking services found").isGreaterThanOrEqualTo(5);
        assertThat(offenders).as("books an appointment without queueing its confirmation "
            + "(PatientNotificationLedger.confirmBooking)" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("every release of a result to the patient queues the result-available notice")
    void everyReleaseIsAnnounced() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String name = file.getFileName().toString();
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            // Imaging: a signature releases the read (ImagingReport.isReleasedToPatient).
            if (source.contains("ImagingReport") && Pattern.compile("\\breport\\.setSignedAt\\(").matcher(source).find()
                && !source.contains("IMAGING_RESULT_AVAILABLE")) {
                offenders.add(name + " (imaging signature)");
            }
            // Ultrasound: reviewed AND the patient told (UltrasoundReport.isReleasedToPatient).
            if (source.contains("UltrasoundReport")
                && (source.contains(".setPatientNotifiedAt(") || source.contains(".setReportReviewedByProvider(true)"))
                && !source.contains("ULTRASOUND_RESULT_AVAILABLE")) {
                offenders.add(name + " (ultrasound release)");
            }
        }
        // Lab: every release path calls patientResultReleased (LabResultReleaseAnnouncementGuardTest),
        // which queues the notice.
        String labNotifier = SourceScan.stripComments(Files.readString(
            MAIN.resolve("com/example/hms/service/lab/LabResultReleaseNotifier.java"), StandardCharsets.UTF_8));
        if (!labNotifier.contains("LAB_RESULT_AVAILABLE")) {
            offenders.add("LabResultReleaseNotifier.java (lab release)");
        }
        assertThat(offenders).as("releases a result to the patient without queueing the result-available notice"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("a new direct SMS send is refused: patient notices go through the ledger")
    void noNewDirectSmsSender() throws IOException {
        Set<String> senders = new TreeSet<>();
        for (Path file : javaFiles(MAIN)) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (DIRECT_SMS.matcher(source).find()) {
                senders.add(file.getFileName().toString());
            }
        }
        assertThat(senders).as("sends an SMS directly instead of through PatientNotificationLedger "
            + "(a patient notice needs its retry, failure view and idempotency key)" + SourceScan.MANDATE)
            .isSubsetOf(DIRECT_SMS_SENDERS);
        assertThat(DIRECT_SMS_SENDERS).as("a sender that no longer sends directly leaves the frozen list")
            .isSubsetOf(senders);
    }

    @Test
    @DisplayName("the result-available templates take the hospital name and nothing else, in every language")
    void resultTemplatesCarryNoClinicalArgument() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern placeholder = Pattern.compile("\\{(\\d+)");
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path bundle : files.filter(f -> f.getFileName().toString().startsWith("messages-notification"))
                .toList()) {
                Properties properties = new Properties();
                try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                for (String key : List.of("sms.result.available", "email.patient.resultAvailable.subject",
                    "email.patient.resultAvailable.body")) {
                    String value = properties.getProperty(key);
                    if (value == null) {
                        offenders.add(bundle.getFileName() + " lacks " + key);
                        continue;
                    }
                    Matcher m = placeholder.matcher(value);
                    while (m.find()) {
                        if (!"0".equals(m.group(1))) {
                            offenders.add(bundle.getFileName() + " " + key + " takes {" + m.group(1) + "}");
                        }
                    }
                }
            }
        }
        assertThat(offenders).as("a result notice names the hospital only: never the test, the value or a "
            + "diagnosis (an SMS is read on a shared phone)").isEmpty();
    }

    @Test
    @DisplayName("the ledger row stores no message, number or address")
    void theRowHoldsNoContactOrText() {
        List<String> fields = Arrays.stream(PatientNotificationDelivery.class.getDeclaredFields())
            .map(Field::getName).map(n -> n.toLowerCase(Locale.ROOT)).toList();
        assertThat(fields).noneMatch(n -> n.contains("phone") || n.contains("email") || n.contains("body")
            || n.contains("message") || n.contains("recipient") || n.contains("address") || n.contains("text"));
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
