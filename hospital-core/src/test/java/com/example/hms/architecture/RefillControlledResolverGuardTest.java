package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * QA D719/D706 re-test guard. {@code Prescription.allowsRefill()} reads only the
 * prescriber's tick, and legacy catalog-controlled morphine has the tick false:
 * the round-1 fix held for new rows and failed on existing ones. Every caller
 * that decides on a refill must also ask {@code PrescriptionControlledResolver},
 * and the pharmacy's no-quantity refusal must ask it too.
 */
class RefillControlledResolverGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    @Test
    void everyRefillDecisionAlsoAsksTheResolver() throws IOException {
        List<Path> callers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            callers = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> read(f).contains(".allowsRefill()"))
                .toList();
        }
        assertThat(callers).as("refill decision sites").hasSizeGreaterThanOrEqualTo(3);
        for (Path f : callers) {
            assertThat(read(f)).as(f + " decides a refill without the catalog")
                .contains("controlledResolver.isControlled(");
        }
    }

    @Test
    void theNoQuantityDispenseRefusalAsksTheResolver() {
        String src = read(MAIN.resolve("service/pharmacy/DispenseServiceImpl.java"));
        int at = src.indexOf("\"pharmacy.dispense.quantityUnknownControlled\"");
        assertThat(at).isPositive();
        String before = src.substring(Math.max(0, at - 300), at);
        assertThat(before).contains("controlledResolver.isControlled(prescription)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages-patient.properties", "messages-patient_en.properties",
        "messages-patient_fr.properties", "messages-patient_es.properties"})
    void refillRefusalsAreLocalised(String bundle) throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/" + bundle)) {
            assertThat(in).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String k : List.of("notRefillable", "onHold", "alreadyPending", "approveNotRefillable")) {
            assertThat(p.getProperty("patient.refill." + k)).as(bundle + " " + k).isNotBlank();
        }
        if (bundle.contains("_fr")) {
            assertThat(p.getProperty("patient.refill.alreadyPending")).contains("renouvellement");
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
