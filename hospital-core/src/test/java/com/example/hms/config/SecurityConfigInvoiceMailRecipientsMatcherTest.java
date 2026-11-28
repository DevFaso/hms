package com.example.hms.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4223 (round 81): the cashier and the accountant got a 403 on the list of a patient's guarantors, which they
 * need to bill and to mail an invoice. The filter chain's blanket {@code GET /patients/**} names neither role, so
 * the controller's own gate was never reached. The read has its own matcher before the blanket, with the same
 * roles as the controller; and the list of addresses an invoice may be mailed to is held to those who may mail it.
 */
class SecurityConfigInvoiceMailRecipientsMatcherTest {

    private static final Path CONFIG = Paths.get("src/main/java/com/example/hms/config/SecurityConfig.java");
    private static final Path GUARANTORS =
        Paths.get("src/main/java/com/example/hms/controller/PatientGuarantorController.java");
    private static final Path INVOICES =
        Paths.get("src/main/java/com/example/hms/controller/BillingInvoiceController.java");

    private static final String GUARANTOR_READ = ".requestMatchers(HttpMethod.GET, API_PATIENTS + \"/*/guarantors\")";
    private static final String PATIENT_BLANKET = ".requestMatchers(HttpMethod.GET, API_PATIENTS, API_PATIENTS_PATTERN)";
    private static final String RECIPIENTS_READ =
        ".requestMatchers(HttpMethod.GET, API_BILLING_INVOICES + \"/*/email-recipients\")";
    private static final String INVOICE_BLANKET = ".requestMatchers(HttpMethod.GET, API_BILLING_INVOICES_PATTERN)";
    private static final String MAIL_SEND = ".requestMatchers(HttpMethod.POST, \"/billing-invoices/*/email\")";

    private static String gateAfter(String source, String line) {
        int at = source.indexOf(line);
        assertThat(at).as(line).isPositive();
        int roles = source.indexOf(".hasAnyAuthority(", at);
        return source.substring(roles, source.indexOf(")", roles));
    }

    @Test
    void theCashierAndTheAccountantReadTheGuarantors_beforeTheBlanketRefusesThem() throws IOException {
        String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertThat(config.indexOf(GUARANTOR_READ)).as("the guarantor read comes before GET /patients/**")
            .isPositive().isLessThan(config.indexOf(PATIENT_BLANKET));
        assertThat(gateAfter(config, GUARANTOR_READ))
            .contains("ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_RECEPTIONIST", "ROLE_HOSPITAL_ADMIN",
                "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE")
            .doesNotContain("ROLE_PATIENT", "ROLE_PHARMACIST");
        // The rest of the patient record stays closed to the two billing roles.
        assertThat(gateAfter(config, PATIENT_BLANKET)).doesNotContain("ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT");
    }

    @Test
    void theControllerAdmitsTheSameReadersAndKeepsTheWritesToTheDesk() throws IOException {
        String controller = Files.readString(GUARANTORS, StandardCharsets.UTF_8);
        int read = controller.indexOf("READ_ROLES =");
        String readers = controller.substring(read, controller.indexOf(";", read));
        assertThat(readers).contains("ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_RECEPTIONIST");
        int write = controller.indexOf(" ROLES =");
        String writers = controller.substring(write, controller.indexOf(";", write));
        assertThat(writers).doesNotContain("ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT");
        // Exactly one handler reads with the wider set: the list.
        assertThat(controller.split("@PreAuthorize\\(READ_ROLES\\)", -1)).hasSize(2);
        int list = controller.indexOf("@PreAuthorize(READ_ROLES)");
        assertThat(controller.lastIndexOf("@GetMapping", list))
            .isGreaterThan(controller.lastIndexOf("@PostMapping", list))
            .isGreaterThan(controller.lastIndexOf("@PutMapping", list));
    }

    @Test
    void theAddressesOnFileAreForThoseWhoMayMailTheInvoice() throws IOException {
        String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertThat(config.indexOf(RECIPIENTS_READ)).as("before the broader GET /billing-invoices/**")
            .isPositive().isLessThan(config.indexOf(INVOICE_BLANKET));
        String readers = gateAfter(config, RECIPIENTS_READ);
        assertThat(readers).isEqualTo(gateAfter(config, MAIL_SEND))
            .doesNotContain("ROLE_RECEPTIONIST", "ROLE_DOCTOR", "ROLE_ACCOUNTANT");

        String controller = Files.readString(INVOICES, StandardCharsets.UTF_8);
        int get = controller.indexOf("@GetMapping(\"/{id}/email-recipients\")");
        int post = controller.indexOf("@PostMapping(\"/{id}/email\")");
        assertThat(get).isPositive();
        String getGate = controller.substring(controller.indexOf("@PreAuthorize(", get),
            controller.indexOf("\")", controller.indexOf("@PreAuthorize(", get)));
        String postGate = controller.substring(controller.indexOf("@PreAuthorize(", post),
            controller.indexOf("\")", controller.indexOf("@PreAuthorize(", post)));
        assertThat(getGate).isEqualTo(postGate);
    }
}
