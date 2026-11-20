package com.example.hms.architecture;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.service.vitals.MaternityVitalCharting;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2580 (r65), the class: a screen that records a vital sign in its own table leaves the chart
 * (« Signes vitaux », the storyboard, NEWS2) without it.
 *
 * <ol>
 *   <li>Every persistent type ({@code @Entity}, {@code @Embeddable}, {@code @MappedSuperclass}, its
 *   superclasses' fields included) holding a field whose name looks like a vital sign is either
 *   {@link PatientVitalSign}, charted through a {@link MaternityVitalCharting} method taking it, or listed in
 *   {@link #NOT_CHARTED} with the reason it stays out of the chart.</li>
 *   <li>Every save of a charted type's repository, anywhere in the code, is followed in the same method by a
 *   {@code maternityVitalCharting.chart…(} call.</li>
 * </ol>
 */
@DisplayName("Every recorded vital sign reaches the chart")
class MaternityVitalChartingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Loose on purpose: a new name for a vital sign should land here, not slip past. */
    private static final Pattern VITAL_NAME = Pattern.compile(
        "systolic|diastolic|heartrate|pulse|temperature|respirat|spo2|oxygensat|saturation");

    /** The charted types, and the repository whose saves must be followed by charting. */
    private static final Map<String, String> CHARTED = Map.of(
        "LaborPartographEntry", "LaborPartographEntryRepository",
        "PostpartumObservation", "PostpartumObservationRepository",
        "NewbornAssessment", "NewbornAssessmentRepository");

    /** Types with a vital-sign-like field that stay out of the chart, each with why. */
    private static final Map<String, String> NOT_CHARTED = Map.of(
        "UltrasoundReport",
        "fetalHeartRate is the fetus's, measured on the scan: not the mother's vital sign",
        "HighRiskBloodPressureLog",
        "home blood pressures the high-risk pregnancy plan logs by DAY with no time, patient-measured: kept out of "
            + "the clinical vitals and NEWS2 like PATIENT_REPORTED rows; the plan's own screen shows them "
            + "(review of #1237, decision)");

    @Test
    void everyTypeWithAVitalSignIsChartedOrIsTheChartOrSaysWhyNot() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        Set<String> holders = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.hms.model")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (type != PatientVitalSign.class && holdsVital(type)) {
                holders.add(type.getSimpleName());
            }
        }
        Set<String> expected = new TreeSet<>(CHARTED.keySet());
        expected.addAll(NOT_CHARTED.keySet());
        assertThat(holders)
            .as("a type storing a vital sign must be charted by MaternityVitalCharting (CHARTED), or say why not "
                + "(NOT_CHARTED)")
            .isEqualTo(expected);

        Set<String> chartable = new TreeSet<>();
        for (Method m : MaternityVitalCharting.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getParameterCount() > 0) {
                chartable.add(m.getParameterTypes()[0].getSimpleName());
            }
        }
        assertThat(chartable).containsAll(CHARTED.keySet());
    }

    @Test
    void everySaveOfAChartedRecordIsCharted() throws IOException {
        List<String> uncharted = new ArrayList<>();
        int saves = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
                uncharted.addAll(unchartedSaves(file.getFileName().toString(), source));
                saves += countSaves(source);
            }
        }
        assertThat(saves).as("the scan finds the three recording paths").isGreaterThanOrEqualTo(3);
        assertThat(uncharted)
            .as("a save of a partograph entry, postpartum observation or newborn assessment is not followed by "
                + "maternityVitalCharting.chart…( in the same method")
            .isEmpty();
    }

    @Test
    void theScanCatchesASaveWithoutCharting() {
        String before = String.join("\n",
            "class X {",
            "    private final PostpartumObservationRepository repo;",
            "",
            "    public void correct(PostpartumObservation o) {",
            "        repo.save(o);",
            "    }",
            "}");
        assertThat(unchartedSaves("X.java", before)).hasSize(1);
        String after = before.replace("repo.save(o);",
            "repo.save(o);\n        maternityVitalCharting.chartPostpartumObservation(o);");
        assertThat(unchartedSaves("X.java", after)).isEmpty();
    }

    private static boolean holdsVital(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                    && VITAL_NAME.matcher(f.getName().toLowerCase(Locale.ROOT)).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countSaves(String source) {
        int n = 0;
        for (String field : repositoryFields(source)) {
            Matcher m = savePattern(field).matcher(source);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    static List<String> unchartedSaves(String file, String source) {
        List<String> out = new ArrayList<>();
        for (String field : repositoryFields(source)) {
            Matcher m = savePattern(field).matcher(source);
            while (m.find()) {
                int end = source.indexOf("\n    }", m.end());
                String rest = source.substring(m.end(), end < 0 ? source.length() : end);
                if (!rest.contains("maternityVitalCharting.chart")) {
                    out.add(file + ":" + field + "." + m.group(1));
                }
            }
        }
        return out;
    }

    private static List<String> repositoryFields(String source) {
        List<String> fields = new ArrayList<>();
        for (String repo : CHARTED.values()) {
            Matcher m = Pattern.compile("\\b" + repo + "\\s+(\\w+)\\s*[;=,)]").matcher(source);
            while (m.find()) {
                fields.add(m.group(1));
            }
        }
        return fields;
    }

    private static Pattern savePattern(String field) {
        return Pattern.compile("\\b" + Pattern.quote(field) + "\\.(save|saveAll|saveAndFlush)\\(");
    }
}
