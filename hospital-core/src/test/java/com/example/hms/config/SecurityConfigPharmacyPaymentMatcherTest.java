package com.example.hms.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D538: POST /pharmacy/payments by a non-billing role answered 400 field
 * errors before the 403, because the body is validated before the method
 * annotation runs. The filter-chain matcher must refuse first, with exactly
 * the roles of the controller's @PreAuthorize.
 */
class SecurityConfigPharmacyPaymentMatcherTest {

    private static final Path CONFIG = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");
    private static final Path CONTROLLER =
        Paths.get("src/main/java/com/example/hms/controller/pharmacy/PharmacyPaymentController.java");

    @Test
    void postPharmacyPayments_isGatedInTheFilterChain_withTheControllerRoles() throws IOException {
        String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
        String line = ".requestMatchers(HttpMethod.POST, \"/pharmacy/payments\")";
        int at = config.indexOf(line);
        assertThat(at).as("POST /pharmacy/payments matcher").isPositive();
        int roles = config.indexOf(".hasAnyAuthority(", at);
        String gate = config.substring(roles, config.indexOf(")", roles));

        String controller = Files.readString(CONTROLLER, StandardCharsets.UTF_8);
        int post = controller.indexOf("@PostMapping");
        int open = controller.indexOf("hasAnyRole(", post) + "hasAnyRole(".length();
        String annotationRoles = controller.substring(open, controller.indexOf(")", open));
        for (String role : annotationRoles.replace("'", "").split(",")) {
            assertThat(gate).contains("ROLE_" + role.trim());
        }
        assertThat(gate).doesNotContain("ROLE_NURSE").doesNotContain("ROLE_DOCTOR");
    }
}
