package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import jakarta.validation.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1258 (round 26; the server half of D1245, NOT_FIXED again): a blank or
 * absent {@code patientId} on {@code POST /reception/waitlist} and
 * {@code POST /birth-plans} answered 404 "Patient introuvable avec ID : null"
 * with an empty {@code fieldErrors}. Round 25 enumerated the ids the server
 * could not READ (a blank string, an unparsable UUID); it missed the ids that
 * read fine as null and went straight into a lookup. Three ways that happens,
 * one rule each, over every request body of every controller:
 *
 * <ul>
 *   <li>a body whose class declares constraints is bound without
 *       {@code @Valid}, so none of them runs (the waitlist and check-in
 *       bodies);</li>
 *   <li>a service looks up {@code request.getXxxId()} although the field is
 *       not {@code @NotNull} and nothing before the lookup refuses a null with
 *       the field named;</li>
 *   <li>a service refuses a null {@code request.getXxxId()} with an exception
 *       that names no field (a {@code BusinessException} message only).</li>
 * </ul>
 *
 * Each refusal must be a 400 with {@code fieldErrors.<field>} before any
 * lookup: {@code @NotNull} on the field (bean validation names it), or a
 * {@link com.example.hms.exception.FieldBusinessException} naming it.
 */
@DisplayName("D1258: a missing required id of a request is refused with its field named, before any lookup")
class RequiredRequestIdGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern LOOKUP = Pattern.compile(
        "\\b(\\w*ById\\w*|\\w+OrThrow|getReferenceById)\\(\\s*+(\\w+)\\.get(\\w+Id)\\(\\)\\s*+\\)");
    private static final Pattern FIELDLESS_NULL_REFUSAL = Pattern.compile(
        "if\\s*+\\(\\s*+(\\w+)\\.get(\\w+Id)\\(\\)\\s*+==\\s*+null\\s*+\\)\\s*+\\{?\\s*+throw\\s++new\\s++(\\w+)\\(");

    /**
     * Lookups the scan reads that are not a request id reaching a lookup unchecked, each with its reason.
     */
    private static final Map<String, String> NOT_A_REQUEST_LOOKUP = Map.of(
        "PatientServiceImpl#createPatient:hospitalId",
        "internal: the legacy backfill and createPatientByStaff call it with the hospital the controller resolved",
        "PharmacySaleServiceImpl#sellingLot:stockLotId",
        "the only caller reads 'getStockLotId() == null ? null : sellingLot(...)' - the lot is optional",
        "StockTransactionServiceImpl#requireReturnOfTransfer:referenceId",
        "called from requireReturnWithinDispense only after its 'getReferenceId() == null' branch returned");

    /**
     * Bodies bound without {@code @Valid} although they declare constraints, each with its reason.
     */
    private static final Map<String, String> UNVALIDATED_ALLOWED = Map.of(
        "StaffController#create",
        "standing debt: StaffRequestDTO puts @NotBlank / @Size on enum fields, which bean validation cannot apply"
            + " (UnexpectedTypeException, a 500); its ids are names the service resolves itself",
        "StaffController#update", "same body as StaffController#create");

    @Test
    @DisplayName("a request body that declares constraints is bound with @Valid")
    void everyConstrainedRequestBodyIsValidated() {
        List<String> unvalidated = new ArrayList<>();
        for (Handler handler : handlers()) {
            if (!handler.validated() && declaresConstraints(handler.body(), new LinkedHashSet<>())
                    && !UNVALIDATED_ALLOWED.containsKey(handler.where())) {
                unvalidated.add(handler.where() + " binds " + handler.body().getSimpleName() + " without @Valid");
            }
        }
        assertThat(unvalidated)
            .as("its @NotNull / @Size never run, so a missing id reaches the service (QA D1258)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a service looks up a request id only when a null was refused with the field named")
    void everyRequestIdIsRequiredBeforeItsLookup() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> unchecked = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        int lookups = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> fieldChecks = fieldNamedNullChecks(source);
            Matcher m = LOOKUP.matcher(source);
            while (m.find()) {
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                Class<?> body = parameterType(method, m.group(2), bodies);
                if (body == null) {
                    continue;
                }
                lookups++;
                String field = Character.toLowerCase(m.group(3).charAt(0)) + m.group(3).substring(1);
                String site = file.getFileName().toString().replace(".java", "") + "#" + methodName(method) + ":" + field;
                if (NOT_A_REQUEST_LOOKUP.containsKey(site)) {
                    excused.add(site);
                    continue;
                }
                String before = method.substring(0, method.indexOf(m.group()) < 0 ? method.length() : method.indexOf(m.group()));
                if (!isNotNull(body, field) && !refusedBefore(before, m.group(3), fieldChecks)
                        && !guardedInStatement(source, m.start(), m.group(3))) {
                    unchecked.add(site + " (" + body.getSimpleName() + "." + field + " at line "
                        + SourceScan.lineOf(source, m.start()) + ")");
                }
            }
        }
        assertThat(lookups).as("request-id lookups read").isGreaterThan(40);
        assertThat(excused).as("an excused lookup that disappeared: delete its line")
            .containsExactlyInAnyOrderElementsOf(NOT_A_REQUEST_LOOKUP.keySet());
        assertThat(unchecked)
            .as("a null id reaches the lookup and answers 404 '... ID : null' with no fieldErrors; make the field"
                + " @NotNull or refuse a null first with a FieldBusinessException naming it (QA D1258)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("a missing request id is refused with an exception that names the field")
    void everyMissingRequestIdRefusalNamesItsField() throws IOException {
        Map<String, Class<?>> bodies = requestBodyTypes();
        List<String> fieldless = new ArrayList<>();
        int refusals = 0;
        for (Path file : serviceFiles()) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = FIELDLESS_NULL_REFUSAL.matcher(source);
            while (m.find()) {
                String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                if (parameterType(method, m.group(1), bodies) == null) {
                    continue;
                }
                refusals++;
                if (!"FieldBusinessException".equals(m.group(3))) {
                    fieldless.add(file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + " "
                        + m.group(1) + ".get" + m.group(2) + "() == null -> " + m.group(3));
                }
            }
        }
        assertThat(refusals).as("null-id refusals read").isGreaterThan(10);
        assertThat(fieldless)
            .as("the 400 names no field, so the form cannot mark it (QA D1258)" + SourceScan.MANDATE)
            .isEmpty();
    }

    // ── helpers ──

    private record Handler(String where, Class<?> body, boolean validated) {
    }

    private static List<Handler> handlers() {
        List<Handler> out = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method m : controller.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(RequestBody.class) && p.getType().getName().startsWith("com.example.hms.")) {
                        boolean validated = p.isAnnotationPresent(Valid.class) || p.isAnnotationPresent(Validated.class);
                        out.add(new Handler(controller.getSimpleName() + "#" + m.getName(), p.getType(), validated));
                    }
                }
            }
        }
        return out;
    }

    /** Simple name to class of every request body and every payload class nested in one. */
    private static Map<String, Class<?>> requestBodyTypes() {
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Handler handler : handlers()) {
            collectNested(handler.body(), seen);
        }
        Map<String, Class<?>> out = new HashMap<>();
        seen.forEach(type -> out.put(type.getSimpleName(), type));
        return out;
    }

    private static void collectNested(Class<?> type, Set<Class<?>> seen) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || !seen.add(type)) {
            return;
        }
        for (Field field : fields(type)) {
            collectNested(field.getType(), seen);
            if (field.getGenericType() instanceof ParameterizedType parameterized) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> element) {
                        collectNested(element, seen);
                    }
                }
            }
        }
    }

    private static boolean declaresConstraints(Class<?> type, Set<Class<?>> seen) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || !seen.add(type)) {
            return false;
        }
        if (hasConstraint(type.getAnnotations())) {
            return true;
        }
        for (Field field : fields(type)) {
            if (hasConstraint(field.getAnnotations())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConstraint(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotNull(Class<?> type, String field) {
        for (Field f : fields(type)) {
            if (f.getName().equals(field)) {
                return f.isAnnotationPresent(jakarta.validation.constraints.NotNull.class);
            }
        }
        return false;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** The request-body type of the parameter (or local) named {@code variable}, or null. */
    private static Class<?> parameterType(String method, String variable, Map<String, Class<?>> bodies) {
        int open = method.indexOf('(');
        int close = SourceScan.closingParen(method, open);
        String params = method.substring(open + 1, close);
        Matcher declared = Pattern.compile("([\\w.]+)\\s++" + Pattern.quote(variable) + "\\b").matcher(params);
        if (!declared.find()) {
            return null;
        }
        String type = declared.group(1);
        return bodies.get(type.substring(type.lastIndexOf('.') + 1));
    }

    private static String methodName(String method) {
        Matcher name = Pattern.compile("(\\w+)\\s*+\\(").matcher(method);
        return name.find() ? name.group(1) : "?";
    }

    /** Method name to the getters it refuses null with a FieldBusinessException, joined. */
    private static Map<String, String> fieldNamedNullChecks(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher check = Pattern.compile(
            "if\\s*+\\(\\s*+\\w+\\.get(\\w+Id)\\(\\)\\s*+==\\s*+null\\s*+\\)\\s*+\\{?\\s*+throw\\s++new\\s++FieldBusinessException\\(")
            .matcher(source);
        while (check.find()) {
            String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, check.start());
            out.merge(methodName(method), check.group(1), (a, b) -> a + "," + b);
        }
        return out;
    }

    /**
     * A null of the getter was dealt with before the lookup: refused with the
     * field named (directly or in a helper called first), or tested at all - an
     * optional id branched on ({@code if (x.getYId() != null) { lookup }}); a
     * test that throws a field-less refusal is the third rule's business.
     */
    private static boolean refusedBefore(String before, String getter, Map<String, String> fieldChecks) {
        if (Pattern.compile("get" + getter + "\\(\\)\\s*+[!=]=\\s*+null|(?:isNull|nonNull|ofNullable)\\(\\s*+\\w+\\.get"
                + getter + "\\(\\)").matcher(before).find()) {
            return true;
        }
        for (Map.Entry<String, String> helper : fieldChecks.entrySet()) {
            if (List.of(helper.getValue().split(",")).contains(getter)
                    && Pattern.compile("\\b" + Pattern.quote(helper.getKey()) + "\\(").matcher(before).find()) {
                return true;
            }
        }
        return false;
    }

    /** {@code x.getYId() == null ? ... : lookup(x.getYId())} or {@code != null ? lookup(...)} in one statement. */
    private static boolean guardedInStatement(String source, int at, String getter) {
        String statement = source.substring(SourceScan.statementStart(source, at), at);
        return Pattern.compile("get" + getter + "\\(\\)\\s*+[!=]=\\s*+null\\s*+\\?").matcher(statement).find();
    }

    private static List<Path> serviceFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve("service"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<Class<?>> controllers() {
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("controller"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(f).toString().replace('\\', '/').replace(".java", "");
                try {
                    out.add(Class.forName("com.example.hms." + rel.replace('/', '.')));
                } catch (ClassNotFoundException e) {
                    // a file whose top-level type is not public-named after it: not a controller
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
