package com.example.hms.architecture;

import com.example.hms.payload.dto.BloodGroupStatusDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3901 (r77, High): the record showed the group a doctor TYPED (« A+ ») in red bold, as if verified, for a
 * patient the laboratory had grouped B+, and never showed the laboratory's grouping at all.
 *
 * <p>Two rules keep that from coming back through a new screen:
 * <ol>
 *   <li>every response type that carries the typed group (a String field named {@code bloodType} or
 *       {@code patientBloodType}) carries a {@link BloodGroupStatusDTO} beside it: the declared group, the
 *       laboratory's confirmed group with where and when, and the discrepancy. A request type, or a response that
 *       already sets the typed group against the laboratory's, is listed below with why;</li>
 *   <li>every main source file that BUILDS such a response with the typed group also fills the status.</li>
 * </ol>
 */
@DisplayName("QA D3901: a typed blood group never travels without the laboratory's")
class BloodGroupDisplayGuardTest {

    private static final String PAYLOAD_PACKAGE = "com.example.hms.payload";
    private static final Pattern TYPED_GROUP_FIELD = Pattern.compile("(?i)(patient)?bloodtype");
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Pattern BUILDS_TYPED_GROUP = Pattern.compile("\\.(patientB|b)loodType\\(");
    private static final Pattern BUILDS_STATUS = Pattern.compile("\\.(patientB|b)loodGroup\\(");

    /** Types that carry the typed group and need no status beside it, and why. */
    private static final Map<String, String> NO_STATUS_NEEDED = Map.of(
        "PatientRequestDTO", "a request: what the registration desk or a clinician types",
        "PatientProfileUpdateRequestDTO", "a request: the demographics edit of a doctor, nurse or midwife",
        "SignupRequest", "a request: what a patient declares at sign-up",
        "PatientRecordDTO", "returned by no endpoint and built by no code");

    /** Main sources that build an object with the typed group that is not a patient-facing response. */
    private static final Map<String, String> BUILDS_NO_RESPONSE = Map.of(
        "DevSyntheticDataSeeder.java", "builds Patient entities for the dev seed: the declared group only");

    @Test
    @DisplayName("every response with the typed group carries the declared/confirmed status beside it")
    void typedGroupTravelsWithTheStatus() throws ClassNotFoundException {
        Set<String> missing = new TreeSet<>();
        Set<String> carriers = new TreeSet<>();
        for (Class<?> type : payloadTypes()) {
            if (type.getSimpleName().endsWith("Builder")) {
                continue; // Lombok's builder of a type checked on its own
            }
            boolean typed = Arrays.stream(type.getDeclaredFields())
                .anyMatch(f -> f.getType() == String.class && TYPED_GROUP_FIELD.matcher(f.getName()).matches());
            if (!typed) {
                continue;
            }
            carriers.add(type.getSimpleName());
            boolean withStatus = Arrays.stream(type.getDeclaredFields())
                .map(Field::getType).anyMatch(BloodGroupStatusDTO.class::equals);
            if (!withStatus && !NO_STATUS_NEEDED.containsKey(type.getSimpleName())) {
                missing.add(type.getName());
            }
        }
        assertThat(missing)
            .as("these responses carry the typed blood group with nothing saying it is declared and not "
                + "verified, nor what the laboratory confirmed: add a BloodGroupStatusDTO beside it, filled by "
                + "PatientBloodGroupStatusService (QA D3901)")
            .isEmpty();
        // The scan sees the types it must, and the exemptions name types that still exist.
        assertThat(carriers).contains("PatientResponseDTO", "PatientHeaderDTO", "NurseWorkboardPatientDTO",
            "AfterVisitSummaryDTO", "PatientProfileDTO");
        assertThat(carriers).containsAll(NO_STATUS_NEEDED.keySet());
    }

    @Test
    @DisplayName("every code path that builds a response with the typed group fills the status too")
    void buildersFillTheStatus() throws IOException {
        List<String> missing = new ArrayList<>();
        Set<String> builders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!BUILDS_TYPED_GROUP.matcher(source).find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                builders.add(name);
                if (!BUILDS_STATUS.matcher(source).find() && !BUILDS_NO_RESPONSE.containsKey(name)) {
                    missing.add(name);
                }
            }
        }
        assertThat(missing)
            .as("these files build a response with the typed blood group and leave its declared/confirmed "
                + "status empty (QA D3901)")
            .isEmpty();
        assertThat(builders).contains("PatientMapper.java", "PatientStoryboardServiceImpl.java",
            "NurseTaskServiceImpl.java", "CheckOutMapper.java", "PatientPortalServiceImpl.java");
        assertThat(builders).containsAll(BUILDS_NO_RESPONSE.keySet());
    }

    private static List<Class<?>> payloadTypes() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(PAYLOAD_PACKAGE)) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        return found;
    }
}
