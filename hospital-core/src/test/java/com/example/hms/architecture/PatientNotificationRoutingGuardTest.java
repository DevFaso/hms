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

    /**
     * Method by method, not file by file: a lab release ({@code setReleased(true)}) is announced in the
     * method that releases, or in a method of the same file that calls it (the autoverifier releases
     * inside {@code performAutoVerification}; {@code createLabResult}, which calls it, announces).
     * A file that announces somewhere is not enough: the file-level rule let a release path that
     * never announced pass because another method of the file did.
     */
    @Test
    @DisplayName("every method that releases a lab result announces it, or its caller in the file does")
    void everyLabReleaseMethodAnnounces() throws IOException {
        List<String> offenders = new ArrayList<>();
        int releasing = 0;
        for (Path file : javaFiles(MAIN)) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!RELEASES_LAB.matcher(source).find()) {
                continue;
            }
            List<String[]> methods = declaredMethods(source);
            for (String[] method : methods) {
                String body = method[1];
                if (!RELEASES_LAB.matcher(body).find()) {
                    continue;
                }
                releasing++;
                if (ANNOUNCES.matcher(body).find()) {
                    continue;
                }
                Pattern call = Pattern.compile("\\b" + Pattern.quote(method[0]) + "\\s*+\\(");
                boolean callerAnnounces = methods.stream().filter(other -> !other[0].equals(method[0]))
                    .anyMatch(other -> call.matcher(other[1]).find() && ANNOUNCES.matcher(other[1]).find());
                if (!callerAnnounces) {
                    offenders.add(file.getFileName() + "#" + method[0]);
                }
            }
        }
        assertThat(releasing).as("lab-release methods found").isGreaterThanOrEqualTo(3);
        assertThat(offenders).as("releases a lab result and neither it nor its caller calls patientResultReleased / "
            + "announceRelease (the patient is never told, by bell, SMS or email)" + SourceScan.MANDATE).isEmpty();
    }

    /**
     * Every failure an SMS gateway's send path throws carries a {@link
     * com.example.hms.service.integration.SmsFailureKeys} key, so the ledger can tell "refused" from
     * "may have gone out". IKODDI threw "request failed with status 500", which the ledger read as a
     * transient failure and resent up to five times. The scan covers {@code sendSms} and every method of
     * the gateway it reaches; a gateway that calls a REST client must also catch the transport failure
     * ({@code RestClientException}), which is where a timeout after the request was written lands.
     */
    @Test
    @DisplayName("every SMS gateway's send failures carry a known key")
    void everyGatewayFailureCarriesAKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        int gateways = 0;
        for (Path file : javaFiles(MAIN.resolve("com/example/hms/service/integration"))) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!Pattern.compile("class\\s+\\w+\\s+implements\\s+IkoddiGateway\\b").matcher(source).find()) {
                continue;
            }
            gateways++;
            List<String[]> declared = declaredMethods(source);
            List<String> names = declared.stream().map(m -> m[0]).distinct().toList();
            Set<String> reached = new TreeSet<>();
            List<String> queue = new ArrayList<>(List.of("sendSms"));
            while (!queue.isEmpty()) {
                String method = queue.remove(0);
                if (!reached.add(method)) {
                    continue;
                }
                String body = declared.stream().filter(m -> m[0].equals(method)).map(m -> m[1])
                    .reduce("", String::concat);
                for (String other : names) {
                    if (!reached.contains(other) && Pattern.compile("\\b" + Pattern.quote(other) + "\\s*+\\(")
                        .matcher(body).find()) {
                        queue.add(other);
                    }
                }
                Matcher thrown = THROWN.matcher(body);
                while (thrown.find()) {
                    String argument = thrown.group(2);
                    if (argument.contains("\"") || !(argument.contains("KEY_") || argument.contains("SmsFailureKeys"))) {
                        offenders.add(file.getFileName() + "#" + method + ": new " + thrown.group(1) + "(" + argument
                            + ")");
                    }
                }
                if (body.contains("requestFailure(")) {
                    offenders.add(file.getFileName() + "#" + method + ": requestFailure (a status text, not a key)");
                }
                if (body.contains(".retrieve()") && !Pattern.compile("catch\\s*+\\(\\s*+RestClientException\\b")
                    .matcher(body).find()) {
                    offenders.add(file.getFileName() + "#" + method + ": no catch of RestClientException");
                }
            }
        }
        assertThat(gateways).as("SMS gateways found").isGreaterThanOrEqualTo(2);
        assertThat(offenders).as("an SMS send failure without a SmsFailureKeys key: the ledger cannot tell a refusal"
            + " from a message that may have gone out" + SourceScan.MANDATE).isEmpty();
        for (String key : com.example.hms.service.integration.SmsFailureKeys.ALL) {
            com.example.hms.service.notification.PatientNotificationErrorCode code =
                com.example.hms.service.notification.PatientNotificationErrorCode.of(new IllegalStateException(key),
                    com.example.hms.enums.NotificationChannel.SMS);
            assertThat(code == com.example.hms.service.notification.PatientNotificationErrorCode.OUTCOME_UNKNOWN)
                .as(key + " maps to " + code).isEqualTo(com.example.hms.service.integration.SmsFailureKeys.UNKNOWN_OUTCOME
                    .equals(key));
        }
    }

    private static final Pattern RELEASES_LAB =
        Pattern.compile("\\.setReleased\\(\\s*+true\\s*+\\)|\\.released\\(\\s*+true\\s*+\\)");
    private static final Pattern ANNOUNCES = Pattern.compile("\\b(?:patientResultReleased|announceRelease|"
        + "announceReleasedResult)\\s*+\\(");
    private static final Pattern THROWN =
        Pattern.compile("new\\s++(IllegalStateException|IllegalArgumentException)\\s*+\\(([^;]*?)\\)\\s*+;");
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "(?:private|public|protected|static|final|synchronized|\\s)++[\\w<>\\[\\],.? ]+?\\s++(\\w++)\\s*+\\([^;{)]*+\\)"
            + "\\s*+(?:throws\\s++[\\w., ]+)?\\{");

    /** Every method declaration as {name, body}, overloads included (each overload has its own body). */
    private static List<String[]> declaredMethods(String source) {
        List<String[]> methods = new ArrayList<>();
        Matcher m = METHOD_DECLARATION.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (!Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "new").contains(name)) {
                int open = m.end() - 1;
                methods.add(new String[] {name, source.substring(open, SourceScan.closingBrace(source, open) + 1)});
            }
        }
        return methods;
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
