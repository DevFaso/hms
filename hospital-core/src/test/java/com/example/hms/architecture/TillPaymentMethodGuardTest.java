package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1611 (round 44): an insurer's share is never counted as money received.
 *
 * <p>Paying a dispense by "Assurance" printed "Total payé" and stored a plain
 * INSURANCE payment, with no claim to the payer; the visit invoice and the
 * check-in copay took the same method. {@code TillPaymentMethods} refuses it.
 * This guard finds every request DTO that carries a payment method (a field
 * typed {@code PaymentMethod} or {@code PharmacyPaymentMethod}, or a string
 * checked against {@code PaymentMethod.CODES}) and requires every service
 * class that reads that field to call the refusal. A new payment entry point
 * fails here until it decides, through that call, what an INSURANCE method means.
 */
@DisplayName("Every payment entry point refuses an insurer's share as money received")
class TillPaymentMethodGuardTest {

    private static final Path DTOS = Path.of("src/main/java/com/example/hms/payload/dto");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final String GUARD_CALL = "TillPaymentMethods.requireReceivedAtTill(";

    /** A typed method field: {@code private PharmacyPaymentMethod paymentMethod;}. */
    private static final Pattern TYPED_FIELD = Pattern.compile(
        "\\b(?:com\\.example\\.hms\\.enums\\.)?(?:Pharmacy)?PaymentMethod\\s++(\\w++)\\s*+;");
    /** A string checked against the method codes, then its field. */
    private static final Pattern CODES_FIELD = Pattern.compile(
        "PaymentMethod\\.CODES[^;]*?\\bprivate\\s++String\\s++(\\w++)\\s*+;", Pattern.DOTALL);

    @Test
    @DisplayName("every service reading a payment method from a request calls TillPaymentMethods")
    void everyReaderRefusesInsurance() throws IOException {
        Map<String, String> dtoFields = paymentMethodRequestDtos();
        // The scan must keep finding the five entry points this guard was written for.
        assertThat(dtoFields).containsKeys("StaffPaymentRequestDTO", "PatientPaymentRequestDTO",
            "CheckInRequestDTO", "PharmacyPaymentRequestDTO", "PharmacySaleRequestDTO");

        List<String> readers = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Path file : javaFiles(SERVICES)) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!code.contains("class ")) {
                continue;
            }
            for (Map.Entry<String, String> dto : dtoFields.entrySet()) {
                String getter = ".get" + Character.toUpperCase(dto.getValue().charAt(0))
                    + dto.getValue().substring(1) + "()";
                if (Pattern.compile("\\b" + dto.getKey() + "\\b").matcher(code).find() && code.contains(getter)) {
                    String reader = file.getFileName() + " reads " + dto.getKey() + "#" + dto.getValue();
                    readers.add(reader);
                    if (!code.contains(GUARD_CALL)) {
                        missing.add(reader);
                    }
                }
            }
        }
        assertThat(readers).as("the scan found the payment readers").hasSizeGreaterThanOrEqualTo(5);
        assertThat(missing)
            .as("a payment entry point that does not refuse INSURANCE through TillPaymentMethods")
            .isEmpty();
    }

    /** {@code DTO simple name -> payment-method field name}, for the request DTOs only. */
    private static Map<String, String> paymentMethodRequestDtos() throws IOException {
        Map<String, String> found = new TreeMap<>();
        for (Path file : javaFiles(DTOS)) {
            String name = file.getFileName().toString().replace(".java", "");
            if (!name.endsWith("RequestDTO")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher typed = TYPED_FIELD.matcher(code);
            if (typed.find()) {
                found.put(name, typed.group(1));
                continue;
            }
            Matcher codes = CODES_FIELD.matcher(code);
            if (codes.find()) {
                found.put(name, codes.group(1));
            }
        }
        return found;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
