package com.example.hms.architecture;

import com.example.hms.payload.dto.ultrasound.UltrasoundOrderRequestDTO;
import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.GestationalDating;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1465 + D1481 (round 35): an impossible pregnancy date or count was saved because each form
 * checked what it had thought of. A class that writes a due date from a request passes through
 * {@link GestationalDating#requireDueDateFitsLmp}, and a request's gestational age has a ceiling.
 */
class PregnancyDatingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path DTOS = MAIN.resolve("payload/dto");

    /** Request classes with a due date, and the service each reaches. */
    private static final Set<String> DUE_DATE_REQUESTS = Set.of("MaternalHistoryRequestDTO.java", "PrenatalScheduleRequestDTO.java");

    private static final List<Path> DUE_DATE_CHECKS = List.of(
        MAIN.resolve("service/impl/MaternalHistoryServiceImpl.java"),
        MAIN.resolve("service/PrenatalSchedulingServiceImpl.java"));

    /**
     * CR-08: the one request whose ceiling is not a bean-validation bound, by the user's ruling that a classification
     * never stops a death from being recorded: an @Max here would refuse the death itself. The rule holds the ceiling.
     */
    private static final Set<String> CEILING_IN_THE_RULES = Set.of("DeathRecordRequestDTO#gestationalAgeWeeks");

    @Test
    void theRequestLeftToTheRulesHasItsCeilingThere() {
        assertThat(com.example.hms.service.support.DeathClassificationRules.isPlausibleGestation(45)).isTrue();
        assertThat(com.example.hms.service.support.DeathClassificationRules.isPlausibleGestation(46)).isFalse();
    }

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void everyRequestWithADueDateIsListedAndItsServiceChecksIt() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DTOS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("RequestDTO.java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("LocalDate estimatedDueDate")) {
                    found.add(file.getFileName().toString());
                }
            }
        }
        assertThat(found)
            .as("a request takes a due date: its service must call GestationalDating.requireDueDateFitsLmp (QA D1465)"
                + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(DUE_DATE_REQUESTS);
        for (Path service : DUE_DATE_CHECKS) {
            assertThat(Files.readString(service, StandardCharsets.UTF_8))
                .as(service + " (QA D1465)").contains("requireDueDateFitsLmp(");
        }
    }

    @Test
    void everyGestationalAgeOfARequestHasACeiling() throws IOException {
        List<String> open = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DTOS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("RequestDTO.java")).toList()) {
                String relative = DTOS.relativize(file).toString().replace('\\', '/').replace('/', '.').replace(".java", "");
                collectOpen(relative, open);
            }
        }
        assertThat(open)
            .as("a request's gestational age in weeks has no @Max (QA D1481)" + SourceScan.MANDATE)
            .isEmpty();
    }

    private static void collectOpen(String relative, List<String> open) {
        Class<?> type;
        try {
            type = Class.forName("com.example.hms.payload.dto." + relative);
        } catch (ClassNotFoundException e) {
            return;
        }
        for (Field field : type.getDeclaredFields()) {
            String name = field.getName();
            boolean weeks = field.getType() == Integer.class && name.toLowerCase(java.util.Locale.ROOT).contains("gestationalage");
            String site = type.getSimpleName() + "#" + name;
            if (weeks && !field.isAnnotationPresent(Max.class) && !CEILING_IN_THE_RULES.contains(site)) {
                open.add(site);
            }
        }
    }

    @ParameterizedTest(name = "{0} weeks: {1} violation(s)")
    @CsvSource({"99, 1", "60, 1", "46, 1", "45, 0", "20, 0", "0, 0", "-1, 1"})
    void theUltrasoundOrderRefusesAnImpossibleAge(int weeks, int violations) {
        UltrasoundOrderRequestDTO request = UltrasoundOrderRequestDTO.builder().gestationalAgeAtOrder(weeks).build();
        assertThat(VALIDATOR.validateProperty(request, "gestationalAgeAtOrder")).hasSize(violations);
    }
}
