package com.example.hms.architecture;

import com.example.hms.service.notification.PatientChannelConsent.Decision;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/** Every writer records the choice atomically; no partial settings save can erase another choice. */
class NotificationPreferenceProvenanceGuardTest {
    @Test
    void everyPreferenceWriterRecordsProvenanceAndSerializesTheAccount() throws Exception {
        Path main = Path.of("src/main/java");
        var writers = new ArrayList<String>();
        try (var paths = Files.walk(main)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (!source.contains("NotificationPreference.builder()")) continue;
                writers.add(path.getFileName().toString());
                assertThat(source).as("provenance in %s", path).contains(".recordChoice(");
                assertThat(source).as("writer lock in %s", path).contains("lockForNotificationPreferences(");
                assertThat(source).as("omitted choices in %s", path).doesNotContain("deleteByUser_Id(");
            }
        }
        assertThat(writers).containsExactlyInAnyOrder("NotificationServiceImpl.java", "PatientChannelPreferenceService.java");
    }

    @Test
    void bothOptOutTransitionsSnapshotTheRefusalRatherThanTheWorker() throws Exception {
        Path source = Path.of("src/main/java/com/example/hms/service/notification");
        for (String file : new String[] {"PatientNotificationLedger.java", "PatientNotificationRenderer.java"}) {
            assertThat(Files.readString(source.resolve(file))).as(file)
                .contains(".map(decision::scopeTo)", "PatientChannelConsent.Decision::recordedBy",
                    "PatientChannelConsent.Decision::recordedAt")
                .doesNotContain("consent.refusal(", ".flatMap(accountId -> consent.");
            assertThat(Files.readString(source.resolve(file))).as("scoped account projection in %s", file)
                .contains("findNotificationAccountScoped(");
        }
        assertThat(Files.readString(source.resolve("NotificationPreferenceViews.java")))
            .contains("findClinicalActorNames(").doesNotContain(".getUsername(", ".getEmail(", ".getPhone(");
        assertThat(Files.readString(source.resolve("PatientMessageGate.java")))
            .contains("var decision = consent.decision(", "channel, decision);");
        assertThat(Files.readString(source.resolve("PatientNotificationLedger.java")))
            .contains("PatientNotificationErrorCode.OPTED_OUT, decision.consent()");
        assertThat(Files.readString(source.resolve("PatientNotificationRenderer.java")))
            .contains("skip(row, refusal.code(), refusal.decision())");
    }

    @Test
    void everyDeliveryProvenanceWriterConsumesAnImmutableDecision() throws Exception {
        var writers = new ArrayList<String>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                if (!text.contains(".setConsentRecordedBy(") && !(text.contains("PatientNotificationDelivery.builder()")
                    && text.contains(".consentRecordedBy("))) continue;
                writers.add(path.getFileName().toString());
                assertThat(text).as("immutable snapshot in %s", path).contains(".map(decision::scopeTo)");
            }
        }
        assertThat(writers).containsExactlyInAnyOrder("PatientNotificationLedger.java", "PatientNotificationRenderer.java");
        for (var component : Decision.class.getRecordComponents()) {
            assertThat(component.getType()).isIn(boolean.class, java.util.UUID.class, java.time.LocalDateTime.class);
        }
    }
}
