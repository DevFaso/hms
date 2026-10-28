package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1008: "8225 F CFA" next to "19.875 F CFA" in Spanish, and amounts read
 * "4,000.00" or "$4000" on screens that did not go through the shared
 * formatter. Every amount of money in the portal goes through {@code formatCfa}
 * (the {@code cfa} pipe in a template): whole francs, the locale's grouping
 * always applied, "F CFA" after the number.
 *
 * <p>Guard: no template pipes a money-like field through {@code number},
 * {@code decimal} or {@code currency}, and no component builds its own
 * {@code style: 'currency'} formatter for francs CFA.
 */
class PortalMoneyFormattingGuardTest {

    private static final Path APP = Path.of("..", "hospital-portal", "src", "app");

    /** A money-like expression piped through a generic number or currency pipe. */
    private static final Pattern MONEY_THROUGH_NUMBER = Pattern.compile(
        "(?i)(amount|balance|price|total|cost|paid|due|fee|copay|charge)\\w*\\s*\\)?\\s*\\|\\s*(number|decimal|currency)\\b");

    /** A component's own Intl currency formatter (formatCfa is the one place that may). */
    private static final Pattern OWN_CURRENCY_FORMAT = Pattern.compile("style:\\s*'currency'");

    /** The shared formatter itself, and the plan screen that prices foreign currencies too. */
    private static final List<String> OWN_FORMAT_ALLOWED = List.of(
        "shared/i18n/app-locale.ts",
        "super-admin/subscriptions/subscriptions.ts");

    @Test
    void everyAmountGoesThroughTheSharedFrancFormatter() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(APP)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = APP.relativize(file).toString().replace('\\', '/');
                if (name.endsWith(".spec.ts")) {
                    continue;
                }
                boolean template = name.endsWith(".html");
                boolean source = name.endsWith(".ts");
                if (!template && !source) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (template && MONEY_THROUGH_NUMBER.matcher(line).find()) {
                        offenders.add(name + ":" + (i + 1) + "  " + line.trim());
                    }
                    if (source && OWN_CURRENCY_FORMAT.matcher(line).find() && !OWN_FORMAT_ALLOWED.contains(name)) {
                        offenders.add(name + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }
        assertThat(offenders).as("amounts formatted outside formatCfa / the cfa pipe").isEmpty();
    }
}
