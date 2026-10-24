package com.example.hms.validation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D714/D745 guard: round 10 fixed the partograph, onset and postpartum
 * times one by one, and the re-test found admission, delivery, newborn and
 * history times still taking the future. Every date/time field of a maternity
 * or newborn request DTO now carries the shared rule ({@link NotInClinicalFuture},
 * or {@code @PastOrPresent}/{@code @Past}), unless it is a planned date named
 * in {@link #PLANNED}. A nested DTO holding such a field must be {@code @Valid}
 * on its parent so the rule actually runs. The allowance may only shrink.
 */
class ClinicalTimestampFutureGuardTest {

    private static final List<String> PACKAGES = List.of(
        "com.example.hms.payload.dto.clinical.labor",
        "com.example.hms.payload.dto.clinical.newborn",
        "com.example.hms.payload.dto.clinical.postpartum",
        "com.example.hms.payload.dto.prenatal");

    private static final List<String> EXTRA_CLASSES = List.of(
        "com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO",
        "com.example.hms.payload.dto.clinical.BirthPlanRequestDTO");

    /** Planned (forward-looking) dates, ClassSimpleName.field. */
    private static final Set<String> PLANNED = Set.of(
        "MenstrualHistoryDTO.estimatedDueDate",
        "MenstrualHistoryDTO.estimatedDueDateByUltrasound",
        "PostpartumObservationRequestDTO.postpartumVisitDate",
        "PrenatalScheduleRequestDTO.estimatedDueDate",
        "PrenatalRescheduleRequestDTO.newAppointmentDate",
        "IntroductionDTO.expectedDueDate");

    private static boolean isTemporal(Class<?> t) {
        return t == LocalDateTime.class || t == LocalDate.class || t == OffsetDateTime.class
            || t == ZonedDateTime.class || t == Instant.class;
    }

    private static boolean hasRule(Field f) {
        return f.isAnnotationPresent(NotInClinicalFuture.class)
            || f.isAnnotationPresent(PastOrPresent.class)
            || f.isAnnotationPresent(Past.class);
    }

    private static List<Class<?>> requestClasses() throws ClassNotFoundException {
        List<Class<?>> out = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().contains("Request"));
        for (String pkg : PACKAGES) {
            for (BeanDefinition bd : scanner.findCandidateComponents(pkg)) {
                out.add(Class.forName(bd.getBeanClassName()));
            }
        }
        for (String name : EXTRA_CLASSES) {
            out.add(Class.forName(name));
        }
        java.util.Set<Class<?>> withNested = new java.util.LinkedHashSet<>();
        for (Class<?> c : out) {
            withNested.add(c);
            withNested.addAll(List.of(c.getDeclaredClasses()));
        }
        // Lombok builders mirror the DTO fields; the constraint lives on the DTO.
        withNested.removeIf(c -> c.getSimpleName().endsWith("Builder"));
        return new ArrayList<>(withNested);
    }

    @Test
    void everyMaternityTimestampRefusesTheFuture() throws Exception {
        List<Class<?>> classes = requestClasses();
        assertThat(classes).hasSizeGreaterThan(10);
        List<String> missing = new ArrayList<>();
        List<String> stalePlanned = new ArrayList<>(PLANNED);
        for (Class<?> c : classes) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !isTemporal(f.getType())) {
                    continue;
                }
                String key = c.getSimpleName() + "." + f.getName();
                if (PLANNED.contains(key)) {
                    stalePlanned.remove(key);
                    continue;
                }
                if (!hasRule(f)) {
                    missing.add(key);
                }
            }
        }
        assertThat(missing).as("clinical timestamps without the future rule").isEmpty();
        assertThat(stalePlanned).as("PLANNED entries that no longer exist (shrink the list)").isEmpty();
    }

    @Test
    void nestedDtosWithTimestampsAreCascaded() throws Exception {
        List<String> notCascaded = new ArrayList<>();
        for (Class<?> c : requestClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getDeclaringClass() == c
                        && hasRuledTemporal(f.getType())
                        && !f.isAnnotationPresent(Valid.class)) {
                    notCascaded.add(c.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(notCascaded).isEmpty();
    }

    private static boolean hasRuledTemporal(Class<?> nested) {
        for (Field f : nested.getDeclaredFields()) {
            if (isTemporal(f.getType()) && hasRule(f)) {
                return true;
            }
        }
        return false;
    }
}
