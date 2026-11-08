package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1134 (round 20): a severity read after a feminine noun agrees with it.
 * The allergy alert read "(gravité : inconnu)" because the severity was taken
 * from the masculine {@code cds.enum.*} words. Every severity in words now
 * comes from {@code cds.severity.*} (feminine in French and Spanish) through
 * {@code CdsCardText.severityWord} or the {@code "cds.severity."} key:
 * <ul>
 *   <li>no main code turns a severity into words through {@code enumWord} or
 *       a {@code "cds.enum." + severity} key;</li>
 *   <li>every severity constant of the allergy, problem and interaction
 *       enums has a {@code cds.severity} word in every locale, and the
 *       French ones agree with a feminine noun.</li>
 * </ul>
 */
class SeverityAgreementGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern MASCULINE_SEVERITY = Pattern.compile(
        "enumWord\\([^;)\\n]*[Ss]everity|\"cds\\.enum\\.\"\\s*+\\+[^;\\n]*[Ss]everity"
            // QA D1168 (round 21): a generic enum-label helper (the record PDF's label(t, code))
            // fed a severity takes the masculine cds.enum word as well.
            + "|\\blabel\\([^;)\\n]*[Ss]everity");

    @Test
    void noSeverityIsWrittenWithTheMasculineEnumWords() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = MASCULINE_SEVERITY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    offenders.add(MAIN.relativize(file) + ": " + m.group());
                }
            }
        }
        assertThat(offenders)
            .as("a severity in words from cds.enum.* reads masculine after \"gravité\" (QA D1134):"
                + " use CdsCardText.severityWord(…) or the cds.severity.* key")
            .isEmpty();
    }

    @Test
    void theScanSeesTheOldShape() {
        assertThat(MASCULINE_SEVERITY.matcher("text.enumWord(a.getSeverity())").find()).isTrue();
        assertThat(MASCULINE_SEVERITY.matcher("resolveOrRaw(\"cds.enum.\" + severityStr)").find()).isTrue();
        assertThat(MASCULINE_SEVERITY.matcher("resolveOrRaw(\"cds.enum.\" + interaction.getSeverity().name())")
            .find()).isTrue();
        assertThat(MASCULINE_SEVERITY.matcher("text.enumWord(existing.getStatus())").find()).isFalse();
        // QA D1168: the record PDF's generic label helper.
        assertThat(MASCULINE_SEVERITY.matcher("label(t, a.getSeverity())").find()).isTrue();
        assertThat(MASCULINE_SEVERITY.matcher("severityLabel(t, a.getSeverity())").find()).isFalse();
        assertThat(MASCULINE_SEVERITY.matcher("label(t, a.getVerificationStatus())").find()).isFalse();
    }

    @Test
    void everySeverityHasAWordThatAgreesInEveryLocale() throws IOException {
        List<Enum<?>> constants = new ArrayList<>();
        for (Class<? extends Enum<?>> type : List.of(com.example.hms.enums.AllergySeverity.class,
                com.example.hms.enums.ProblemSeverity.class, com.example.hms.enums.InteractionSeverity.class)) {
            constants.addAll(List.of(type.getEnumConstants()));
        }
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            Properties bundle = load("i18n/messages-clinical" + suffix + ".properties");
            for (Enum<?> c : constants) {
                assertThat(bundle.getProperty("cds.severity." + c.name()))
                    .as("cds.severity." + c.name() + " in messages-clinical" + suffix).isNotBlank();
            }
            assertThat(bundle.getProperty("cds.severity.UNKNOWN")).as("UNKNOWN" + suffix).isNotBlank();
        }
        Properties fr = load("i18n/messages-clinical_fr.properties");
        for (String name : List.of("MILD", "MODERATE", "UNKNOWN", "CONTRAINDICATED", "MAJOR", "MINOR")) {
            assertThat(fr.getProperty("cds.severity." + name)).as("French " + name + " is feminine").endsWith("e");
        }
        assertThat(fr.getProperty("cds.severity.UNKNOWN")).isEqualTo("inconnue");
        assertThat(load("i18n/messages-clinical_es.properties").getProperty("cds.severity.UNKNOWN"))
            .isEqualTo("desconocida");
    }

    private static Properties load(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = SeverityAgreementGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}
