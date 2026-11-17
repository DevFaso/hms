package com.example.hms.architecture;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.service.allergy.CurrentAllergyRecheck;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1584 / D1585 (round 43, retest 2): after a prescriber's CONTINUE review the gates let the dose
 * through, but the eMAR, the nurse-station MAR and the pharmacy panels still said "a prescriber must
 * review this conflict", and the nurse never saw the plan (monitoring, premedication) the dose is given
 * under. Every gate response that carries the re-check's allergy cards now carries the stop's review
 * state with them, read on the token the gate itself checks:
 *
 * <ul>
 *   <li>the re-check hands its cards out only as a {@link CurrentAllergyRecheck.Reading}, never as a bare
 *       list: no public method of the re-check (or of the reading) returns cards;</li>
 *   <li>a reading reaches a response only through {@code applyTo}, which sets the cards and the state
 *       together; nothing else calls {@code setAllergyCards(};</li>
 *   <li>every nurse or pharmacy response DTO with an allergy card list implements
 *       {@code AllergyReviewCarrier} and declares the {@code allergyReview} state.</li>
 * </ul>
 */
class AllergyReviewStateGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The response packages of the gates: the eMAR and nurse station, and the pharmacy. */
    private static final List<String> GATE_DTO_DIRS = List.of("payload/dto/nurse/", "payload/dto/pharmacy/");

    private static final Pattern CARD_LIST_FIELD = Pattern.compile(
        "private\\s+(?:java\\.util\\.)?List<(?:[\\w.]+\\.)?CdsCard>\\s+(\\w+)\\s*;");
    private static final Pattern READS = Pattern.compile(
        "CurrentAllergyRecheck\\s*\\.\\s*read\\(|\\.readFor\\(");

    @Test
    void theReCheckHandsOutNoBareCardList() {
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : List.of(CurrentAllergyRecheck.class, CurrentAllergyRecheck.Reading.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && List.class.isAssignableFrom(method.getReturnType())
                        && method.getGenericReturnType().getTypeName().contains(CdsCard.class.getSimpleName())) {
                    leaks.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(leaks)
            .as("a gate's allergy cards leave the re-check only with their review state (Reading#applyTo, QA D1584)")
            .isEmpty();
    }

    @Test
    void everyReadingIsAppliedWithItsStateAndNothingElseSetsTheCards() throws IOException {
        Map<String, String> sources = sources();
        List<String> problems = new ArrayList<>();
        int readers = 0;
        for (Map.Entry<String, String> file : sources.entrySet()) {
            String src = file.getValue();
            if (file.getKey().startsWith("service/allergy/")
                    || file.getKey().equals("payload/dto/prescription/AllergyReviewCarrier.java")) {
                continue;
            }
            if (READS.matcher(src).find()) {
                readers++;
                if (!src.contains(".applyTo(")) {
                    problems.add(file.getKey() + " reads the allergy cards and never applies them with their review state");
                }
            }
            if (src.contains("setAllergyCards(") && !src.contains("implements com.example.hms.payload.dto.prescription.AllergyReviewCarrier")) {
                problems.add(file.getKey() + " sets the allergy cards without their review state");
            }
        }
        assertThat(readers).as("the scan found the gates that show the allergy cards").isGreaterThanOrEqualTo(2);
        assertThat(problems).as("QA D1584%s", SourceScan.MANDATE).isEmpty();
    }

    @Test
    void everyGateResponseWithAllergyCardsCarriesTheReviewState() throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> carriers = new ArrayList<>();
        sources().forEach((file, src) -> {
            if (GATE_DTO_DIRS.stream().noneMatch(file::startsWith)) {
                return;
            }
            var fields = CARD_LIST_FIELD.matcher(src);
            if (!fields.find()) {
                return;
            }
            carriers.add(file);
            if (!src.contains("AllergyReviewCarrier")) {
                problems.add(file + " carries allergy cards (" + fields.group(1) + ") and is not an AllergyReviewCarrier");
            }
            if (!Pattern.compile("AllergyReviewStateDTO\\s+allergyReview\\s*;").matcher(src).find()) {
                problems.add(file + " carries allergy cards and no allergyReview state");
            }
        });
        assertThat(carriers).as("the gate responses that carry allergy cards").contains(
            "payload/dto/nurse/NurseMedicationTaskResponseDTO.java",
            "payload/dto/nurse/MarVerificationResponseDTO.java",
            "payload/dto/pharmacy/WorkQueuePrescriptionDTO.java");
        assertThat(problems)
            .as("a gate response with an allergy card says whether the prescriber reviewed it (QA D1584)%s",
                SourceScan.MANDATE)
            .isEmpty();
    }

    /** The source, comments stripped, of every main file. */
    private static Map<String, String> sources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                out.put(rel, SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }
}
