package com.example.hms.architecture;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing-money plan, "New acceptance criterion" (owner, 2026-11-15): e-Keneya never collects card numbers, CVVs or
 * PINs. Every payment and refund free-text field a request can write (a reference, a note, a reason) carries
 * {@link NoCardNumber}, which refuses a Luhn-valid 13-19 digit value with a FR/EN message that never repeats it.
 *
 * <p>The scan finds every request payload whose name says payment or refund, plus the pharmacy counter sale and the
 * check-in copay reference, and requires the annotation on each of their text fields. A field checked against a code
 * list ({@code @Pattern}: a method, an operator) or a currency code is not free text. A new payment or refund request
 * fails here until its text fields are covered.
 */
@DisplayName("Every payment and refund free-text field refuses a card number")
class CardDataFreeTextGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PAYLOAD = MAIN.resolve("payload");
    private static final java.util.regex.Pattern PAYMENT_REQUEST =
        java.util.regex.Pattern.compile(".*(?:Payment|Refund).*Request.*");

    /** Payloads that carry payment text without saying so in their name: class -> their payment text fields. */
    private static final Map<String, Set<String>> ALSO = Map.of(
        "com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO", Set.of("referenceNumber", "notes"),
        "com.example.hms.payload.dto.CheckInRequestDTO", Set.of("copayReference"));

    /** Not free text: a currency code. */
    private static final Set<String> NOT_FREE_TEXT = Set.of("currency");

    @Test
    void everyPaymentAndRefundTextFieldRefusesACardNumber() throws Exception {
        Map<Class<?>, Set<String>> scanned = new TreeMap<>(java.util.Comparator.comparing(Class::getSimpleName));
        for (String name : paymentRequestClasses()) {
            Class<?> type = Class.forName(name);
            scanned.put(type, textFields(type));
        }
        for (Map.Entry<String, Set<String>> also : ALSO.entrySet()) {
            scanned.merge(Class.forName(also.getKey()), also.getValue(), (a, b) -> {
                Set<String> merged = new TreeSet<>(a);
                merged.addAll(b);
                return merged;
            });
        }
        // The scan must keep finding the payment and refund entry points it was written for.
        assertThat(scanned.keySet().stream().map(Class::getSimpleName).toList()).contains("StaffPaymentRequestDTO",
            "PatientPaymentRequestDTO", "PaymentReasonRequestDTO", "PharmacyPaymentRequestDTO",
            "PharmacySaleRequestDTO", "CheckInRequestDTO", "InvoiceRefundRequestDTO", "InvoiceRefundReasonRequestDTO");

        List<String> missing = new ArrayList<>();
        int fields = 0;
        for (Map.Entry<Class<?>, Set<String>> entry : scanned.entrySet()) {
            for (String fieldName : entry.getValue()) {
                fields++;
                Field field = entry.getKey().getDeclaredField(fieldName);
                if (field.getAnnotation(NoCardNumber.class) == null) {
                    missing.add(entry.getKey().getSimpleName() + "#" + fieldName);
                }
            }
        }
        assertThat(fields).as("payment text fields found").isGreaterThanOrEqualTo(12);
        assertThat(missing)
            .as("a payment or refund text field that does not refuse a card number: add @NoCardNumber")
            .isEmpty();
    }

    /** Every free-text field: a String that is not a code checked against a list, nor a currency. */
    private static Set<String> textFields(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                    || field.getAnnotation(Pattern.class) != null || NOT_FREE_TEXT.contains(field.getName())) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    private static List<String> paymentRequestClasses() throws IOException {
        try (Stream<Path> files = Files.walk(PAYLOAD)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> PAYMENT_REQUEST.matcher(f.getFileName().toString().replace(".java", "")).matches())
                .map(f -> "com.example.hms." + MAIN.relativize(f).toString()
                    .replace('\\', '.').replace('/', '.').replace(".java", ""))
                .sorted()
                .toList();
        }
    }
}
