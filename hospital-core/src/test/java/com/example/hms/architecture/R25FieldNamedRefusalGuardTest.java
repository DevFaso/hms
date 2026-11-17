package com.example.hms.architecture;

import com.example.hms.config.RequestBodyDefaultsModule;
import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.testsupport.SourceScan;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 25 guards for the records branch.
 *
 * <ul>
 *   <li><b>D1245</b> (the D1089 / D1124 class, NOT_FIXED four rounds running):
 *       "Nouvelle ordonnance" without a patient answered 400 with an EMPTY
 *       {@code fieldErrors}. The earlier fixes covered the portal forms they
 *       were shown; the server half was never enumerated. Every request body
 *       of every controller is read here: an empty instance must produce only
 *       violations that name a field (a class-level rule reports on one), and
 *       every UUID, enum or date field sent blank or unparsable must come back
 *       with {@code fieldErrors} naming it.</li>
 *   <li><b>D1246</b>: every path that writes an after-visit summary at the
 *       end of a visit goes through {@code AfterVisitSummaryContent.require}.</li>
 *   <li><b>D1248</b>: every path that saves a vaccination refuses the same dose
 *       twice.</li>
 * </ul>
 */
@DisplayName("Round 25: every refusal names its field; every summary and dose write goes through its rule")
class R25FieldNamedRefusalGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final JsonMapper MAPPER = JsonMapper.builder().addModule(new RequestBodyDefaultsModule()).build();

    private static final Set<Class<?>> PARSED_SCALARS = Set.of(UUID.class, LocalDate.class, LocalDateTime.class,
        LocalTime.class, Instant.class, OffsetDateTime.class);

    @Test
    @DisplayName("D1245: an empty request body is refused only with violations that name a field")
    void everyRequestBodyViolationNamesAField() throws Exception {
        List<String> nameless = new ArrayList<>();
        int bodies = 0;
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (Class<?> body : requestBodies(true)) {
                Object empty = newInstance(body);
                if (empty == null) {
                    continue;
                }
                bodies++;
                for (ConstraintViolation<Object> v : validator.validate(empty)) {
                    if (v.getPropertyPath().toString().isEmpty()) {
                        nameless.add(body.getSimpleName() + ": " + v.getMessageTemplate());
                    }
                }
            }
        }
        assertThat(bodies).as("validated request bodies read").isGreaterThan(150);
        assertThat(nameless)
            .as("a class-level rule that refuses with no field, so the body's fieldErrors is empty (QA D1245)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("D1245: a blank or unparsable UUID, enum or date names its field in fieldErrors")
    void everyParsedFieldIsNamedWhenItCannotBeRead() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        List<String> unnamed = new ArrayList<>();
        int fields = 0;
        for (Class<?> body : requestBodies(false)) {
            if (newInstance(body) == null) {
                continue;
            }
            for (Field field : allFields(body)) {
                Class<?> type = field.getType();
                if (!PARSED_SCALARS.contains(type) && !type.isEnum()) {
                    continue;
                }
                String name = jsonName(field);
                fields++;
                for (String sent : List.of("\"\"", "\"not-a-value\"")) {
                    String json = "{\"" + name + "\":" + sent + "}";
                    try {
                        MAPPER.readValue(json, body);
                        // Read as null (blank): the bean-validation path names it (test above).
                    } catch (RuntimeException ex) {
                        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(
                            new HttpMessageNotReadableException("unreadable", ex,
                                new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8))), request);
                        Object errors = ((Map<?, ?>) response.getBody()).get("fieldErrors");
                        if (!(errors instanceof Map<?, ?> map) || !map.containsKey(name)) {
                            unnamed.add(body.getSimpleName() + "." + name + " sent " + sent);
                        }
                    }
                }
            }
        }
        assertThat(fields).as("UUID / enum / date fields read").isGreaterThan(300);
        assertThat(unnamed)
            .as("a UUID, enum or date the server cannot read, refused without naming the field (QA D1245)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("D1246: every path that writes a checkout's after-visit summary checks it says something")
    void everyCheckoutSummaryWriterRequiresContent() throws IOException {
        int writers = 0;
        for (Path file : javaFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = Pattern.compile("\\bupsertDischargeSummaryForCheckout\\(").matcher(source);
            while (m.find()) {
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (method.contains("void upsertDischargeSummaryForCheckout(")) {
                    continue;
                }
                writers++;
                assertThat(method).as(file + ": a checkout path that saves an empty after-visit summary (QA D1246)")
                    .contains("AfterVisitSummaryContent.require(");
            }
        }
        assertThat(writers).as("checkout summary writers").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("D1248: every path that saves a vaccination refuses the same dose twice")
    void everyImmunizationSaveRefusesADuplicateDose() throws IOException {
        int saves = 0;
        for (Path file : javaFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = Pattern.compile("\\bimmunizationRepository\\s*+\\.\\s*+save(?:AndFlush)?\\(").matcher(source);
            while (m.find()) {
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (method.contains("setActive(false)") || method.contains("setReminderSent(")) {
                    continue; // a removal or a reminder stamp: no new dose
                }
                saves++;
                assertThat(method).as(file + ": a vaccination saved without the duplicate-dose rule (QA D1248)")
                    .contains("refuseDuplicateDose(");
            }
        }
        assertThat(saves).as("vaccination saves that write a dose").isGreaterThanOrEqualTo(2);
    }

    // ── helpers ──

    /** The {@code @RequestBody} types of every handler; with {@code validatedOnly}, those also {@code @Valid}. */
    private static Set<Class<?>> requestBodies(boolean validatedOnly) throws Exception {
        Set<Class<?>> out = new java.util.LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
                Class<?> type;
                try {
                    type = Class.forName("com.example.hms." + rel.replace('/', '.'));
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Method m : type.getDeclaredMethods()) {
                    for (Parameter p : m.getParameters()) {
                        if (p.isAnnotationPresent(RequestBody.class)
                                && (!validatedOnly || p.isAnnotationPresent(jakarta.validation.Valid.class)
                                    || p.isAnnotationPresent(org.springframework.validation.annotation.Validated.class))
                                && p.getType().getName().startsWith("com.example.hms.")
                                && !p.getType().isEnum() && !p.getType().isInterface()
                                && !Modifier.isAbstract(p.getType().getModifiers())) {
                            out.add(p.getType());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Object newInstance(Class<?> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static String jsonName(Field field) {
        com.fasterxml.jackson.annotation.JsonProperty p = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        return p != null && !p.value().isEmpty() ? p.value() : field.getName();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
