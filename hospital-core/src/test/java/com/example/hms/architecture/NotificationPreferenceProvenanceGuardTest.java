package com.example.hms.architecture;

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
                .contains("consent.refusal(", "NotificationPreference::getRecordedByUserId", "NotificationPreference::getRecordedAt");
            assertThat(Files.readString(source.resolve(file))).as("scoped account projection in %s", file)
                .contains("findNotificationAccountScoped(");
        }
        assertThat(Files.readString(source.resolve("NotificationPreferenceViews.java")))
            .contains("findClinicalActorNames(").doesNotContain(".getUsername(", ".getEmail(", ".getPhone(");
    }
}
