package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** Complements the all-sender inventory in PatientMessageGateGuardTest, without excluding a sender. */
class PatientRecipientVerificationGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    @Test void everyPatientTransportChokePointChecksRecipientOwnership() throws Exception {
        String gate = Files.readString(MAIN.resolve("service/notification/PatientMessageGate.java"));
        assertThat(gate.split("verified\\(patient, notice", -1)).hasSize(5);
        assertThat(gate).contains("verification.allows(patient, channel, recipient)", "ledger.recordUnverified(");
        assertThat(gate).contains("currentCheck.isVerified(patientId, NotificationChannel.SMS, phone)");
        assertThat(Files.readString(MAIN.resolve("service/notification/PatientRecipientCurrentCheck.java")))
            .contains("Propagation.REQUIRES_NEW", "verification.allows(patient, channel, recipient)");
        assertThat(gate).contains("VerifiedPatientMailScope.run(patient.getId(), to");
        assertThat(Files.readString(MAIN.resolve("service/notification/PatientNotificationRenderer.java")))
            .contains("verification.allows(patient, row.getChannel(), recipient)", "RECIPIENT_UNVERIFIED");
        assertThat(Files.readString(MAIN.resolve("service/mail/MailOutboxDispatchService.java")))
            .contains("getClinicalPatientId()", "verification.allows(patient", "RecipientUnverified");
        assertThat(Files.readString(MAIN.resolve("service/mail/MailOutboxService.java")))
            .contains("VerifiedPatientMailScope.current()", "setClinicalPatientId", "setVerifiedPatientRecipient");
    }
    @Test void challengeContainsOnlySecurityContentAndTheContactChangesClearProof() throws Exception {
        String service = Files.readString(MAIN.resolve("service/notification/PatientRecipientChallengeService.java"));
        assertThat(service).contains("encoder.encode(code)", "patients.lockByIdUnscoped", "noRollbackFor = BusinessException.class");
        assertThat(service).contains("Propagation.NOT_SUPPORTED", "PROPAGATION_REQUIRES_NEW", "email.sendWithAttachment(");
        assertThat(service).doesNotContain("email.sendHtml(");
        assertThat(service).doesNotContain("getFullName", "getMedical", "getDiagnosis", "log.");
        String patient = Files.readString(MAIN.resolve("model/Patient.java"));
        assertThat(patient).contains("verifiedSmsRecipient = null", "verifiedEmailRecipient = null");
        try (var files = Files.walk(MAIN)) {
            var scopes = files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try { return Files.readString(path).contains("VerifiedPatientMailScope.run("); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }).map(path -> path.getFileName().toString()).toList();
            assertThat(scopes).containsExactly("PatientMessageGate.java");
        }
    }
}
