package com.example.hms.architecture;

import com.example.hms.enums.NotificationType;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2201 / D2202 / D2206 class: "a patient message that ignores the patient's channel choice".
 * The appointment e-mails, the visit-summary e-mail, the pharmacy SMS, the waitlist and recall SMS
 * and the invoice e-mail each called the SMS or mail service themselves, so the patient's opt-out
 * on « Préférences de notification » stopped only the queued notices.
 *
 * <p>The rule: only {@code PatientMessageGate} (and the ledger, which checks the same consent)
 * may hold the raw SMS or mail transport to message a patient. Every other holder is on the list
 * below with the reason it is not a patient notice: a security or account message (exempt, the
 * patient must be able to recover their account whatever they chose), a message to staff, a message
 * to a partner pharmacy, or the transport itself. The list only shrinks. A new class that needs to
 * message a patient uses the gate; a new class on the list needs a reviewer to agree it never
 * writes to a patient.
 */
@DisplayName("Patient SMS and e-mail pass the one consent gate (D2201/D2202/D2206)")
class PatientMessageGateGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Holds a raw SMS or mail transport: a field or constructor parameter, a provider, or a bean lookup. */
    static final Pattern HOLDS_TRANSPORT = Pattern.compile(
        "\\b(?:SmsService|EmailService|MailOutboxService|JavaMailSender|IkoddiGateway|TwilioSmsService"
            + "|ObjectProvider<\\s*(?:SmsService|EmailService)\\s*>)\\s+\\w+\\s*[;,)=]"
            + "|\\b(?:SmsService|EmailService|JavaMailSender|IkoddiGateway)\\.class\\b");

    /** The appointment templates are patient mail: only the gate renders them. */
    static final Pattern APPOINTMENT_TEMPLATE = Pattern.compile("\\.sendAppointment\\w*Email\\s*\\(");

    private static final String SECURITY = "exempt: security / account message (codes, reset, recovery, activation)";
    private static final String STAFF = "staff message, never a patient";
    private static final String PARTNER = "message to a partner pharmacy, never a patient";
    private static final String TRANSPORT = "the transport itself";

    /** Every class that may hold the raw transport, and why. Only shrinks. */
    static final Map<String, String> ALLOWED = Map.ofEntries(
        entry("PatientMessageGate.java", "the gate"),
        entry("PatientNotificationLedger.java", "the queue: asks deliversReal*() only; consent via PatientChannelConsent"),
        entry("PatientNotificationDispatchService.java", "the queue's sender: the renderer re-checks consent"),
        entry("MailOutboxDispatchService.java", TRANSPORT),
        entry("EmailServiceImpl.java", TRANSPORT),
        entry("IkoddiSmsServiceImpl.java", TRANSPORT),
        // Exempt security and account flows (listed in the D2201 PR body).
        entry("AuthNotificationFacade.java", SECURITY),
        entry("PasswordResetServiceImpl.java", SECURITY),
        entry("UsernameReminderServiceImpl.java", SECURITY),
        entry("OwnEmailChangeService.java", SECURITY),
        entry("UserCredentialLifecycleServiceImpl.java", SECURITY),
        entry("PhoneVerificationServiceImpl.java", SECURITY),
        entry("PatientRecipientChallengeService.java", SECURITY + " (D2205 contact ownership code only; no clinical content)"),
        entry("PasswordRotationScheduler.java", SECURITY),
        entry("UserServiceImpl.java", SECURITY),
        entry("UserRoleHospitalAssignmentServiceImpl.java", STAFF + " (role assignment codes)"),
        entry("CriticalValueNotificationService.java", STAFF + " (critical lab value page)"),
        entry("ImagingCriticalNotificationService.java", STAFF + " (critical imaging page)"),
        entry("ProScreeningEscalationService.java", STAFF + " (PRO screening escalation)"),
        entry("ScheduledReportService.java", STAFF + " (scheduled reports)"),
        entry("PatientPortalServiceImpl.java", STAFF + " (refill request to the care team); its patient mail uses the gate"),
        entry("PrescriptionSmsDispatchServiceImpl.java", PARTNER),
        entry("PartnerPharmacySmsTransport.java", PARTNER));

    @Test
    void partnerTransportCannotAcceptPatientsOrArbitraryDestinations() throws IOException {
        String source = SourceScan.stripComments(Files.readString(MAIN.resolve(
            "com/example/hms/service/pharmacy/partner/PartnerPharmacySmsTransport.java")));
        assertThat(source).contains("send(Pharmacy partner, String message)", "partner.getPhoneNumber()");
        assertThat(source).doesNotContain("Patient", "getPhoneNumberPrimary", "getPhoneNumberSecondary");
        String channel = SourceScan.stripComments(Files.readString(MAIN.resolve(
            "com/example/hms/service/pharmacy/partner/SmsPartnerNotificationChannel.java")));
        assertThat(holdsTransport(channel)).isFalse();
        assertThat(channel).doesNotContain("patientPhone(", "getPhoneNumberPrimary", "getPhoneNumberSecondary");
        assertThat(channel).contains("patientMessages.smsAfterCommit(patient,");
    }

    static boolean holdsTransport(String source) {
        return HOLDS_TRANSPORT.matcher(SourceScan.stripComments(source)).find();
    }

    @Test
    @DisplayName("the scan sees a raw transport however it is held")
    void scanSeesTheShapes() {
        assertThat(holdsTransport("class A { private final SmsService smsService; }")).isTrue();
        assertThat(holdsTransport("class A { A(EmailService mail, int x) {} }")).isTrue();
        assertThat(holdsTransport("class A { private final ObjectProvider<SmsService> sms; }")).isTrue();
        assertThat(holdsTransport("class A { void f() { ctx.getBean(EmailService.class); } }")).isTrue();
        assertThat(holdsTransport("class A { private final PatientMessageGate gate; }")).isFalse();
        assertThat(holdsTransport("class A { // private final SmsService smsService;\n }")).isFalse();
    }

    @Test
    @DisplayName("only the gate and the reviewed list hold the SMS or mail transport")
    void onlyTheGateHoldsTheTransport() throws IOException {
        TreeSet<String> holders = new TreeSet<>();
        for (Path file : javaFiles()) {
            if (holdsTransport(Files.readString(file, StandardCharsets.UTF_8))) {
                holders.add(file.getFileName().toString());
            }
        }
        assertThat(holders).as("holds the SMS or mail transport outside PatientMessageGate: a patient message"
            + " must go through the gate (the patient's channel choice, and the opt-out trace staff see);"
            + " a security, staff or partner sender is added to ALLOWED with its reason" + SourceScan.MANDATE)
            .isSubsetOf(ALLOWED.keySet());
        assertThat(ALLOWED.keySet()).as("a class that no longer holds the transport leaves the list")
            .isSubsetOf(holders);
    }

    @Test
    @DisplayName("the appointment e-mail templates are rendered by the gate only")
    void appointmentTemplatesOnlyInTheGate() throws IOException {
        Map<String, Integer> callers = new TreeMap<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (name.equals("EmailService.java") || name.equals("EmailServiceImpl.java")) {
                continue;
            }
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            long calls = APPOINTMENT_TEMPLATE.matcher(source).results().count();
            if (calls > 0) {
                callers.put(name, (int) calls);
            }
        }
        assertThat(callers.keySet()).as("an appointment e-mail sent around PatientMessageGate.appointmentEmail"
            + SourceScan.MANDATE).containsExactly("PatientMessageGate.java");
    }

    @Test
    @DisplayName("every patient message names the preference that turns it off; direct events are never queued")
    void everyEventNamesItsPreference() {
        assertThat(PatientNotificationEvent.values()).allSatisfy(event -> {
            assertThat(event.preferenceType()).as(event + " preference").isNotNull();
            assertThat(event.name().length()).as(event + " fits event_type VARCHAR(40)").isLessThanOrEqualTo(40);
        });
        List<PatientNotificationEvent> pharmacy = Arrays.stream(PatientNotificationEvent.values())
            .filter(e -> e.name().startsWith("PHARMACY_")).toList();
        assertThat(pharmacy).isNotEmpty().allSatisfy(e -> {
            assertThat(e.preferenceType()).isEqualTo(NotificationType.MEDICATION_REFILL);
            assertThat(e.isQueued()).isFalse();
        });
        assertThat(PatientNotificationEvent.VISIT_SUMMARY.preferenceType()).isEqualTo(NotificationType.DISCHARGE_SUMMARY);
        assertThat(PatientNotificationEvent.INVOICE.preferenceType()).isEqualTo(NotificationType.BILLING);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
