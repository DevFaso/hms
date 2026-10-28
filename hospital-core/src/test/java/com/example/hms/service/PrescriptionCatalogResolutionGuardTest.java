package com.example.hms.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D922/D935 guard: no prescription is written without its catalogue item
 * being resolved first.
 *
 * <p>The form sent no catalogue id, so morphine was never controlled and the
 * paediatric ceiling never fired. The resolution lives in one place,
 * {@code PrescriptionServiceImpl.applyCatalogPick}: this test fails when a
 * prescription entity is built anywhere but {@code PrescriptionMapper}, when
 * the mapper's writers are called from anywhere but
 * {@code PrescriptionServiceImpl}, or when a writer call there is not preceded
 * by {@code applyCatalogPick} in the same method.
 */
class PrescriptionCatalogResolutionGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Pattern BUILDS_ENTITY = Pattern.compile(
        "new (com\\.example\\.hms\\.model\\.)?Prescription\\(\\)|\\bPrescription\\.builder\\(\\)");
    private static final Pattern WRITER = Pattern.compile("prescriptionMapper\\.(toEntity|updateEntity)\\(");

    @Test
    void everyPrescriptionWriteGoesThroughTheCatalogResolution() throws IOException {
        List<String> offenders = new ArrayList<>();
        int writerCalls = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String text = Files.readString(file);
                if (BUILDS_ENTITY.matcher(text).find() && !name.equals("PrescriptionMapper.java")) {
                    offenders.add(name + " builds a Prescription outside PrescriptionMapper");
                }
                Matcher writer = WRITER.matcher(text);
                while (writer.find()) {
                    writerCalls++;
                    if (!name.equals("PrescriptionServiceImpl.java")) {
                        offenders.add(name + " calls prescriptionMapper." + writer.group(1));
                        continue;
                    }
                    int methodStart = text.lastIndexOf("\n    public ", writer.start());
                    String body = text.substring(Math.max(methodStart, 0), writer.start());
                    if (!body.contains("applyCatalogPick(")) {
                        offenders.add("PrescriptionServiceImpl." + writer.group(1)
                            + " at offset " + writer.start() + " runs before applyCatalogPick");
                    }
                }
            }
        }
        assertThat(writerCalls).isGreaterThanOrEqualTo(2);
        assertThat(offenders).isEmpty();
    }
}
