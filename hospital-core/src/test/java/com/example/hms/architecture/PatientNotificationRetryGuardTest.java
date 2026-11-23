package com.example.hms.architecture;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PatientNotificationRetryGuardTest {
    @Test void retryIsBoundFromHttpToTheConditionalDatabaseWrite() throws Exception {
        Path root = Path.of("src/main/java/com/example/hms");
        assertThat(Files.readString(root.resolve("controller/PatientNotificationDeliveryController.java")))
            .contains("@Valid @RequestBody PatientNotificationRetryDTO", "request.expectedManualRetries()");
        assertThat(Files.readString(root.resolve("service/notification/PatientNotificationDeliveryAdminService.java")))
            .contains("row.getManualRetries() != expectedManualRetries", ".retryable()", ".retryable(retryable(row))");
        assertThat(Files.readString(root.resolve("repository/PatientNotificationDeliveryRepository.java")))
            .contains("d.manualRetries = :expectedManualRetries");
        assertThat(Files.readString(Path.of("../hospital-portal/src/app/patient-notifications/patient-notifications.ts")))
            .contains("this.service.retry(row.id, row.manualRetries)", "this.maybeDelivered(row)");
        assertThat(Files.readString(Path.of("../hospital-portal/src/app/services/patient-notification-delivery.service.ts")))
            .contains("{ expectedManualRetries }");
    }
}
