package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r66 D2939: #1241 moved the partograph's maternal pulse and temperature alerts to the WHO Labour Care
 * Guide alert values (pulse 120 and above, 37.5 °C and above) as a PROVISIONAL rule, but their text still
 * read as a settled one, while the hypotension, bradycardia, hypothermia and shock-index alerts beside
 * them said « Règle provisoire ». Every maternal vital alert the partograph raises
 * ({@code labor-maternal-*} in {@code LaborServiceImpl}) says so, in every language: a provisional
 * threshold is never shown to a midwife as a validated one.
 */
@DisplayName("The partograph's maternal vital alerts say their rule is provisional")
class LabourMaternalAlertProvisionalWordingTest {

    private static final Path LABOR_SERVICE =
        Paths.get("src/main/java/com/example/hms/service/impl/LaborServiceImpl.java");
    private static final Path BUNDLES = Paths.get("src/main/resources/i18n");

    private static final Pattern CODE = Pattern.compile("\"(labor-maternal-[a-z-]+)\"");

    private static final Map<String, String> PROVISIONAL = Map.of(
        "messages-maternity.properties", "Provisional rule.",
        "messages-maternity_en.properties", "Provisional rule.",
        "messages-maternity_fr.properties", "Règle provisoire.",
        "messages-maternity_es.properties", "Regla provisional.");

    @Test
    void everyMaternalVitalAlertSaysProvisional() throws IOException {
        Set<String> codes = new TreeSet<>();
        Matcher matcher = CODE.matcher(Files.readString(LABOR_SERVICE, StandardCharsets.UTF_8));
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertThat(codes).as("the maternal vital alert codes of the partograph")
            .contains("labor-maternal-fever", "labor-maternal-tachycardia", "labor-maternal-hypotension")
            .hasSizeGreaterThanOrEqualTo(7);

        for (Map.Entry<String, String> bundle : PROVISIONAL.entrySet()) {
            Properties messages = new Properties();
            try (Reader reader = Files.newBufferedReader(BUNDLES.resolve(bundle.getKey()), StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            for (String code : codes) {
                assertThat(messages.getProperty("labor.alert.msg." + code))
                    .as("labor.alert.msg.%s in %s", code, bundle.getKey())
                    .isNotNull()
                    .endsWith(bundle.getValue());
            }
        }
    }
}
