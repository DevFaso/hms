package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * QA r70 scorecard C5, rule CRITICAL_RESULT_ESCALATION. The class this guards: an escalation that time ends, or
 * that tells the wrong people. The old chain re-sent to the same prescriber, copied the hospital administrators
 * with the value, and stopped after 12 rounds with no final alert.
 */
class CriticalResultEscalationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path SERVICE = MAIN.resolve("service/CriticalValueNotificationService.java");

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no round cap: the sweep reads every unacknowledged critical result, and no setting can stop it")
    void noRoundCap() throws IOException {
        String service = code(SERVICE);
        assertThat(service).contains("labResultRepository.findCriticalUnacknowledged()")
            .doesNotContain("maxEscalationRounds").doesNotContain("max-rounds").doesNotContain("maxRounds");
        String repository = code(MAIN.resolve("repository/LabResultRepository.java"));
        int at = repository.indexOf("findCriticalUnacknowledged()");
        String query = repository.substring(repository.lastIndexOf("@org.springframework.data.jpa.repository.Query(", at), at);
        assertThat(query).contains("r.acknowledged = false").contains("r.criticalReadBackAt IS NULL")
            .doesNotContain("criticalEscalationLevel").doesNotContain(":cutoff");
        assertThat(Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8))
            .doesNotContain("hms.lab.critical-escalation.max-rounds")
            .doesNotContain("hms.lab.critical-escalation.escalate-after-minutes");
        // The settings a hospital may change are minutes and a name: nothing switches the ladder off.
        assertThat(code(MAIN.resolve("service/lab/CriticalResultEscalationSettingsService.java")))
            .doesNotContain("enabled").doesNotContain("disable");
    }

    @Test
    @DisplayName("no administrator on a value-bearing step: their role is named in one place, for a flag with no value")
    void administratorsReceiveNoValue() throws IOException {
        assertThat(code(SERVICE)).doesNotContain("ROLE_HOSPITAL_ADMIN").doesNotContain("ROLE_SUPER_ADMIN");
        String recipients = code(MAIN.resolve("service/lab/CriticalResultRecipients.java"));
        assertThat(recipients.split("ADMINISTRATOR_ROLE", -1)).as("declared once, read once").hasSize(3);
        // The flag's text takes the minutes and nothing else: no test, no value, no patient.
        for (String bundle : List.of("messages-lab.properties", "messages-lab_en.properties",
                "messages-lab_fr.properties", "messages-lab_es.properties")) {
            Properties texts = new Properties();
            try (InputStream in = Files.newInputStream(Path.of("src/main/resources/i18n", bundle))) {
                texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String flag = texts.getProperty("lab.critical.unresolved.admin");
            assertThat(flag).as(bundle).isNotBlank().contains("{0}")
                .doesNotContain("{1}").doesNotContain("{2}").doesNotContain("{3}");
            assertThat(texts.getProperty("lab.critical.unresolved")).as(bundle).contains("{3}");
        }
        String service = code(SERVICE);
        int flagMethod = service.indexOf("private void flagAdministrators(");
        String body = service.substring(flagMethod, service.indexOf("\n    }\n", flagMethod));
        assertThat(body).contains("UNRESOLVED_ADMIN_KEY, new Object[]{minutes}").doesNotContain("buildText")
            .doesNotContain("valueArg");
    }

    @Test
    @DisplayName("only a read-back ends the chain, and it records the role and the channel")
    void onlyAnAcknowledgementEndsIt() throws IOException {
        String service = code(SERVICE);
        assertThat(service).contains("result.setCriticalAckRole(role);")
            .contains("result.setCriticalAckChannel(");
        // The one write that stops the sweep is the read-back's; a step only moves the level forward.
        assertThat(service.split("setAcknowledged\\(true\\)", -1)).hasSize(2);
        String controller = code(MAIN.resolve("controller/LabResultController.java"));
        int at = controller.indexOf("@PostMapping(\"/{id}/critical-read-back\")");
        assertThat(controller.substring(at, controller.indexOf("public ResponseEntity", at)))
            .contains("holdsOwnRole(authentication, 'DOCTOR','NURSE','MIDWIFE')")
            .doesNotContain("ADMIN");
    }

    @Test
    @DisplayName("the escalation texts every SMS, and every SMS goes to a staff account, never to the patient")
    void noSmsToThePatient() throws IOException {
        String service = code(SERVICE);
        assertThat(service).doesNotContain("getPatient().getPhone").doesNotContain("patient.getPhone")
            .doesNotContain("getPhoneNumberE164");
        // Two senders: the prescriber's number at T0, the chain's staff accounts afterwards.
        assertThat(service.split("smsService\\.send\\(", -1)).hasSize(3);
        assertThat(service).contains("users.findByUsernameInIgnoreCase(lower)");
    }
}
