package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1293 (round 27, class guard; user decision: "a payment needs a
 * method"). A staff payment posted with no method was recorded as OTHER and
 * counted in the till, and a check-in copay with none was recorded as cash.
 * Class: a payment method guessed when the request gives none. No main source
 * may default a missing payment method ({@code x == null ? PaymentMethod.Y},
 * {@code isBlank() ? PaymentMethod.Y}, or the same for the pharmacy's
 * {@code PharmacyPaymentMethod}); a missing method is refused with the field
 * named.
 */
class PaymentMethodRequiredGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** A null/blank test whose ternary yields a payment method constant, in one statement. */
    private static final Pattern DEFAULTED = Pattern.compile(
        "(?:==\\s*+null|isBlank\\(\\)|isEmpty\\(\\))[^;?]*+\\?\\s*+(?:\\w++\\.)*+(?:Pharmacy)?PaymentMethod\\.[A-Z_]++");
    private static final Pattern OR_ELSE = Pattern.compile(
        "orElse\\(\\s*+(?:\\w++\\.)*+(?:Pharmacy)?PaymentMethod\\.[A-Z_]++\\s*+\\)");

    @Test
    void noMissingPaymentMethodIsGuessed() throws IOException {
        List<String> guessed = new ArrayList<>();
        int sources = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                if (!src.contains("PaymentMethod")) {
                    continue;
                }
                sources++;
                for (Pattern p : List.of(DEFAULTED, OR_ELSE)) {
                    Matcher m = p.matcher(src);
                    while (m.find()) {
                        guessed.add(MAIN.relativize(f) + ":" + SourceScan.lineOf(src, m.start()) + " " + m.group());
                    }
                }
            }
        }
        assertThat(sources).as("sources handling a payment method").isGreaterThan(5);
        assertThat(guessed).as("a missing payment method is guessed instead of refused with the field named "
            + "(QA D1293)" + SourceScan.MANDATE).isEmpty();
    }
}
