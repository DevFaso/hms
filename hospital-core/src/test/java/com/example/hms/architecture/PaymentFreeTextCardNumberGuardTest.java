package com.example.hms.architecture;

import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.validation.NoCardNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing plan, owner decision D-15 (2026-11-15): e-Keneya never collects card
 * numbers, CVVs or PINs. Every free-text field of a payment or refund request
 * (reference, notes, reason) carries {@link NoCardNumber}, which refuses a
 * Luhn-valid 13–19 digit value with an FR/EN message and never repeats it.
 *
 * <p>A payment or refund request is found two ways, so a new one cannot slip
 * past: it carries a payment method (a field typed {@code PaymentMethod} or
 * {@code PharmacyPaymentMethod}, or a string checked against
 * {@code PaymentMethod.CODES}), or its name says Payment or Refund. Every
 * {@code String} field of such a request must carry the constraint, unless it
 * is listed below with the reason it is not payment free text.
 */
@DisplayName("Every payment and refund free-text field refuses a card number")
class PaymentFreeTextCardNumberGuardTest {

    private static final Path DTOS = Path.of("src/main/java/com/example/hms/payload/dto");
    private static final String DTO_PACKAGE = "com.example.hms.payload.dto";

    /** "Dto.field" → why it is not payment free text. */
    private static final Map<String, String> NOT_PAYMENT_TEXT = new TreeMap<>();

    static {
        NOT_PAYMENT_TEXT.put("StaffPaymentRequestDTO.method", "a method code, checked against PaymentMethod.CODES");
        NOT_PAYMENT_TEXT.put("PatientPaymentRequestDTO.paymentMethod", "a method code, checked against PaymentMethod.CODES");
        NOT_PAYMENT_TEXT.put("PharmacyPaymentRequestDTO.currency", "a currency code (XOF), at most 10 characters");
        NOT_PAYMENT_TEXT.put("PharmacySaleRequestDTO.currency", "a currency code (XOF), at most 10 characters");
        NOT_PAYMENT_TEXT.put("CheckInRequestDTO.chiefComplaint", "the visit's clinical reason, not the copay");
        NOT_PAYMENT_TEXT.put("CheckInRequestDTO.notes", "the receptionist's check-in notes, not the copay");
        NOT_PAYMENT_TEXT.put("CheckInRequestDTO.consentSignedName", "the consent signer's name, not the copay");
        NOT_PAYMENT_TEXT.put("InvoiceRefundRequestDTO.method", "a method code, checked against PaymentMethod.CODES");
    }

    @Test
    @DisplayName("every String field of a payment or refund request carries @NoCardNumber")
    void everyPaymentFreeTextFieldRefusesACardNumber() throws Exception {
        List<Class<?>> requests = paymentRequests();
        assertThat(requests).extracting(Class::getSimpleName)
            .as("the scan must keep finding the payment entry points this guard was written for")
            .contains("StaffPaymentRequestDTO", "PatientPaymentRequestDTO", "CheckInRequestDTO",
                "PharmacyPaymentRequestDTO", "PharmacySaleRequestDTO", "PaymentReasonRequestDTO",
                // Billing-money plan PR 8: the refund request and the reason a refund is rejected or withdrawn.
                "InvoiceRefundRequestDTO", "InvoiceRefundReasonRequestDTO",
                // Billing-money plan PR 7: the payer's reason for rejecting its share of an invoice.
                "PayerShareRejectRequestDTO");

        Set<String> checked = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (Class<?> dto : requests) {
            for (Field field : dto.getDeclaredFields()) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String key = dto.getSimpleName() + "." + field.getName();
                if (NOT_PAYMENT_TEXT.containsKey(key)) {
                    continue;
                }
                checked.add(key);
                if (!field.isAnnotationPresent(NoCardNumber.class)) {
                    missing.add(key);
                }
            }
        }
        assertThat(checked).as("the payment free-text fields found")
            .contains("StaffPaymentRequestDTO.reference", "StaffPaymentRequestDTO.notes",
                "PatientPaymentRequestDTO.transactionReference", "PatientPaymentRequestDTO.notes",
                "CheckInRequestDTO.copayReference", "PharmacyPaymentRequestDTO.referenceNumber",
                "PharmacyPaymentRequestDTO.notes", "PharmacySaleRequestDTO.referenceNumber",
                "PharmacySaleRequestDTO.notes", "PaymentReasonRequestDTO.reason",
                "InvoiceRefundRequestDTO.reference", "InvoiceRefundRequestDTO.reason",
                "InvoiceRefundReasonRequestDTO.reason", "PayerShareRejectRequestDTO.reason");
        assertThat(missing)
            .as("a payment or refund free-text field that would store a card number (D-15): add @NoCardNumber,"
                + " or list it in NOT_PAYMENT_TEXT with the reason it is not payment text")
            .isEmpty();
    }

    @Test
    @DisplayName("the refusal has its own FR and EN wording and never repeats the value")
    void theRefusalIsWordedInFrenchAndEnglish() throws IOException {
        String key = NoCardNumber.MESSAGE_KEY;
        String fr = bundle("messages-common_fr.properties").getProperty(key);
        String en = bundle("messages-common_en.properties").getProperty(key);
        assertThat(fr).as("FR").isNotBlank().contains("carte");
        assertThat(en).as("EN").isNotBlank().contains("card");
        assertThat(fr).isNotEqualTo(en);
        // Nothing interpolates the rejected value into the answer (and so into any log of it).
        assertThat(List.of(fr, en)).noneMatch(m -> m.contains("validatedValue") || m.contains("{0}"));
    }

    private static Properties bundle(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = PaymentFreeTextCardNumberGuardTest.class.getResourceAsStream("/i18n/" + name)) {
            assertThat(in).as(name).isNotNull();
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static List<Class<?>> paymentRequests() throws IOException, ClassNotFoundException {
        List<Class<?>> found = new ArrayList<>();
        for (Path file : javaFiles()) {
            String simple = file.getFileName().toString().replace(".java", "");
            if (!simple.endsWith("RequestDTO")) {
                continue;
            }
            String relative = DTOS.relativize(file).toString().replace('\\', '/').replace(".java", "");
            Class<?> dto = Class.forName(DTO_PACKAGE + "." + relative.replace('/', '.'));
            if (isPaymentRequest(dto)) {
                found.add(dto);
            }
        }
        return found;
    }

    private static boolean isPaymentRequest(Class<?> dto) {
        String name = dto.getSimpleName();
        // Billing-money plan PR 7: the payer's reason for rejecting its share is money free text too.
        if (name.contains("Payment") || name.contains("Refund") || name.contains("PayerShare")) {
            return true;
        }
        for (Field field : dto.getDeclaredFields()) {
            if (field.getType() == PaymentMethod.class || field.getType() == PharmacyPaymentMethod.class) {
                return true;
            }
            jakarta.validation.constraints.Pattern pattern =
                field.getAnnotation(jakarta.validation.constraints.Pattern.class);
            if (pattern != null && PaymentMethod.CODES.equals(pattern.regexp())) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(DTOS)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
