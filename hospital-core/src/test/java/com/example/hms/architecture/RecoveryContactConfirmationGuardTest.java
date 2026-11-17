package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryContactConfirmationGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    @Test
    void everyRecoverySendEndpointAcceptsExplicitReadBackAndTheServiceChecksItBeforeSideEffects() throws Exception {
        var endpoints = new ArrayList<String>();
        try (var files = Files.walk(MAIN.resolve("controller"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file));
                if (code.contains(".sendRecoveryContactVerificationCode(")) {
                    endpoints.add(file.getFileName().toString());
                    assertThat(code).contains("@Valid @RequestBody com.example.hms.payload.dto.credential.RecoveryContactConfirmationRequest confirmation",
                        "contactId, confirmation.confirmedContactValue()");
                }
            }
        }
        assertThat(endpoints).containsExactly("AuthController.java");
        assertThat(Files.readString(MAIN.resolve("payload/dto/credential/RecoveryContactConfirmationRequest.java")))
            .contains("@NotBlank(message = \"{users.recovery.confirmationRequired}\")");
        String body = SourceScan.methodBody(Files.readString(MAIN.resolve("service/UserCredentialLifecycleServiceImpl.java")),
            "sendRecoveryContactVerificationCode");
        int check = body.indexOf("!confirmedContactValue.equals(contact.getContactValue())");
        assertThat(check).isPositive();
        assertThat(body.indexOf("contact.getUser().getId().equals(userId)")).isLessThan(check);
        for (String effect : List.of("requireRecoverySendAllowed(", "generateVerificationCode(", "recoveryContactRepository.save(", "sendRecoverySms(", "sendRecoveryEmail(")) {
            assertThat(body.indexOf(effect)).as(effect).isGreaterThan(check);
        }
    }
}
