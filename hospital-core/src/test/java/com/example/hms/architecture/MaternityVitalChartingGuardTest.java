package com.example.hms.architecture;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.service.vitals.MaternityVitalCharting;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2580 (r65), the class: a screen that records a vital sign in its own table leaves the chart
 * (« Signes vitaux », the storyboard, NEWS2) without it. Every JPA entity holding a vital-sign column
 * (a heart rate, blood pressure, temperature, respiratory rate or SpO₂) is either
 * {@link PatientVitalSign} itself or charted through a {@link MaternityVitalCharting} method taking
 * it, which its recording service calls (each service's test verifies the call). A new entity with
 * a vital-sign column fails here until it is charted.
 */
@DisplayName("Every recorded vital sign reaches the chart")
class MaternityVitalChartingGuardTest {

    private static final Pattern VITAL_FIELD = Pattern.compile(
        "^(heartratebpm|pulsebpm|systolicbpmmhg|diastolicbpmmhg|temperaturecelsius|respiratoryratebpm"
            + "|respirationspermin|spo2percent|oxygensaturationpercent)$");

    /** The entity, and the service whose recording path charts it (the one the guard reads). */
    private static final Map<String, String> CHARTED = Map.of(
        "LaborPartographEntry", "service/impl/LaborServiceImpl.java",
        "PostpartumObservation", "service/impl/PostpartumCareServiceImpl.java",
        "NewbornAssessment", "service/impl/NewbornAssessmentServiceImpl.java");

    @Test
    void everyEntityWithAVitalSignIsChartedOrIsTheChart() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> holders = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.hms.model")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (type == PatientVitalSign.class) {
                continue;
            }
            boolean holdsVital = Arrays.stream(type.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .anyMatch(n -> VITAL_FIELD.matcher(n.toLowerCase(Locale.ROOT)).matches());
            if (holdsVital) {
                holders.add(type.getSimpleName());
            }
        }
        assertThat(holders)
            .as("an entity storing a vital sign must be charted by MaternityVitalCharting (or a sibling) "
                + "and listed in CHARTED")
            .isEqualTo(new TreeSet<>(CHARTED.keySet()));

        Set<String> chartable = new TreeSet<>();
        for (Method m : MaternityVitalCharting.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getParameterCount() > 0) {
                chartable.add(m.getParameterTypes()[0].getSimpleName());
            }
        }
        assertThat(chartable).containsAll(CHARTED.keySet());
    }

    @Test
    void eachRecordingServiceCallsTheCharting() throws Exception {
        for (Map.Entry<String, String> e : CHARTED.entrySet()) {
            String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/example/hms", e.getValue()));
            assertThat(source)
                .as("%s must chart its %s", e.getValue(), e.getKey())
                .containsPattern("maternityVitalCharting\\s*\\.\\s*chart" + e.getKey() + "\\(");
        }
    }
}
