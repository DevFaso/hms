package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1001 (class guard): the dispense form pre-filled the prescribed
 * quantity (20, 40) on a partly filled order, and the server refused it ("il
 * reste au plus 10"). A pharmacy page fills a quantity field from what is
 * still owed ({@code remainingQuantity}), never from the prescription's
 * {@code quantity}.
 */
class PharmacyRemainingQuantityGuardTest {

    private static final Path PHARMACY = Path.of("..", "hospital-portal", "src", "app", "pharmacy");
    /** {@code this.form.quantityDispensed = rx.quantity ...}: a form quantity fed the prescribed amount. */
    private static final Pattern PRESCRIBED_PREFILL = Pattern.compile(
            "\\.\\w*[Qq]uantity\\w*\\s*=\\s*\\w+\\??\\.quantity\\b");

    @Test
    void noPharmacyFormPreFillsThePrescribedQuantity() throws IOException {
        List<String> prefills = new ArrayList<>();
        try (Stream<Path> files = Files.list(PHARMACY)) {
            for (Path ts : files.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts"))
                    .toList()) {
                String source = Files.readString(ts, StandardCharsets.UTF_8);
                Matcher m = PRESCRIBED_PREFILL.matcher(source);
                while (m.find()) {
                    prefills.add(ts.getFileName() + ": " + m.group());
                }
            }
        }
        assertThat(prefills).as("pharmacy forms pre-filled with the prescribed, not the remaining, quantity "
                + "(QA D1001)").isEmpty();
    }

    @Test
    void theScanSeesTheOldPreFill() {
        assertThat(PRESCRIBED_PREFILL.matcher("this.form.quantityDispensed = rx.quantity ?? 0;").find()).isTrue();
        assertThat(PRESCRIBED_PREFILL.matcher("this.form.quantityDispensed = owed;").find()).isFalse();
    }
}
