package com.example.hms.i18n;

import com.example.hms.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1377 (round 32, regression of D1374): the label of a refusal is read
 * from {@code field.label.<property>}, one key per property NAME. Round 31
 * relabelled {@code field.label.to} as "period end" for {@code ?to=}, and the
 * invoice e-mail body's recipient {@code to} became "fin de la période".
 *
 * <p>Class: one label key serving fields of different meanings. This test
 * renders the label of every request-body field (in the context of the DTO
 * that declares it) and of every query parameter (in the context of its
 * controller), in English, French and Spanish, through the resolution the
 * refusal handler uses: a parameter reads {@code field.label.param.<name>}
 * first, a body field reads {@code field.label.<name>}. Two contexts of the
 * same name - a body field and a parameter, or two body fields - whose values
 * are of different natures (a date, a number, a flag, a text) must not render
 * the same label: that is one key carrying two
 * meanings, and renaming it for one silently renames the other.
 */
@DisplayName("D1377: a label key never serves two fields of different meanings")
class FieldLabelMeaningTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");
    private static final List<String> LANGUAGES = List.of("en", "fr", "es");

    /**
     * Names whose contexts differ in value nature but share one meaning, each
     * with its reason. A new entry needs the same: never a silent exclusion.
     */
    private static final Map<String, String> SAME_MEANING = Map.of(
        "alcoholUse", "a yes/no answer in the social history, a free-text answer in the lifestyle factors - both alcohol use",
        "occupationalHazards", "a yes/no answer in the lifestyle factors, a free-text answer in the social history - both hazards",
        "priority", "a rule's evaluation order and an order's clinical priority both read 'priority'",
        "page", "a zero-based page number everywhere; ProviderAdminController reads it as text and parses it itself");

    private final MessageSource source = TestMessageSources.bundles();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("no two contexts of different value natures render the same label, in any language")
    void oneLabelOneMeaning() throws IOException {
        List<Context> contexts = contexts();
        assertThat(contexts).as("request contexts found").hasSizeGreaterThan(1000);
        Map<String, List<Context>> byName = new TreeMap<>();
        contexts.forEach(c -> byName.computeIfAbsent(c.name(), k -> new ArrayList<>()).add(c));
        List<String> shared = new ArrayList<>();
        for (String lang : LANGUAGES) {
            Locale locale = Locale.forLanguageTag(lang);
            byName.forEach((name, list) -> {
                if (!SAME_MEANING.containsKey(name)) {
                    collectSharedLabels(list, locale, shared);
                }
            });
        }
        assertThat(shared)
            .as("one label key names fields of different meanings (QA D1377): give the query parameter its own"
                + " field.label.param.<name>, or list the name in SAME_MEANING with the reason")
            .isEmpty();
    }

    private void collectSharedLabels(List<Context> list, Locale locale, List<String> out) {
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Context a = list.get(i);
                Context b = list.get(j);
                // Two query parameters of one name are one URL concept: a
                // controller that reads ?from= as text parses the same date.
                if (a.nature() == b.nature() || a.parameter() && b.parameter()) {
                    continue;
                }
                String la = label(a, locale);
                if (la != null && la.equals(label(b, locale))) {
                    out.add(locale + ": " + a + " and " + b + " both read \"" + la + "\"");
                    return;
                }
            }
        }
    }

    @Test
    @DisplayName("the invoice e-mail recipient reads as a recipient, and ?from= / ?to= as the ends of a period")
    void recipientAndPeriodKeepTheirMeaning() {
        Map<String, List<String>> expected = Map.of(
            "en", List.of("recipient", "period start", "period end"),
            "fr", List.of("destinataire", "début de la période", "fin de la période"),
            "es", List.of("destinatario", "inicio del período", "fin del período"));
        for (String lang : LANGUAGES) {
            Locale locale = Locale.forLanguageTag(lang);
            List<String> words = expected.get(lang);
            assertThat(label(new Context("to", Nature.TEXT, false, "EmailInvoiceRequest"), locale))
                .as(lang + " EmailInvoiceRequest.to").isEqualTo(words.get(0));
            assertThat(label(new Context("from", Nature.TIME, true, "param"), locale))
                .as(lang + " ?from=").isEqualTo(words.get(1));
            assertThat(label(new Context("to", Nature.TIME, true, "param"), locale))
                .as(lang + " ?to=").isEqualTo(words.get(2));
        }
    }

    /**
     * The QA path: POST /api/billing-invoices/{id}/email and
     * /api/invoices/{id}/email with an empty or blank recipient, through bean
     * validation and the real handler, in every language.
     */
    @Test
    @DisplayName("an empty or blank invoice e-mail recipient is refused naming the recipient, not a period")
    void emailRecipientRefusalNamesTheRecipient() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(source);
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/billing-invoices/x/email");
        Map<String, String> recipient = Map.of("en", "recipient", "fr", "destinataire", "es", "destinatario");
        List<com.example.hms.payload.dto.EmailInvoiceRequest> bodies = List.of(
            new com.example.hms.payload.dto.EmailInvoiceRequest(List.of(), null, null, null, null, false),
            // {to:['']}: only @NotBlank refuses an empty address (@Email accepts an empty string), so the
            // reason is deterministic; a blank " " also trips @Email, and which one a Set yields first varies.
            new com.example.hms.payload.dto.EmailInvoiceRequest(List.of(""), null, null, null, null, false));
        for (String lang : LANGUAGES) {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
            for (com.example.hms.payload.dto.EmailInvoiceRequest target : bodies) {
                org.springframework.validation.BeanPropertyBindingResult result =
                    new org.springframework.validation.BeanPropertyBindingResult(target, "emailInvoiceRequest");
                try (jakarta.validation.ValidatorFactory factory =
                         jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                    new org.springframework.validation.beanvalidation.SpringValidatorAdapter(factory.getValidator())
                        .validate(target, result);
                }
                org.springframework.web.bind.MethodArgumentNotValidException ex =
                    new org.springframework.web.bind.MethodArgumentNotValidException(
                        new org.springframework.core.MethodParameter(Object.class.getMethod("toString"), -1), result);
                Map<?, ?> body = (Map<?, ?>) handler.handleValidationException(ex, request).getBody();
                Map<?, ?> fieldErrors = (Map<?, ?>) body.get("fieldErrors");
                assertThat(fieldErrors).as(lang + " " + target.to()).hasSize(1);
                String reason = String.valueOf(fieldErrors.values().iterator().next());
                assertThat(reason).as(lang + " " + target.to())
                    .startsWith(recipient.get(lang))
                    .doesNotContainIgnoringCase("period")
                    .doesNotContainIgnoringCase("période")
                    .doesNotContainIgnoringCase("período");
            }
        }
    }

    /**
     * The handler names a query parameter by the label this test resolves:
     * every nameable parameter, every language, through the real
     * {@code handleTypeMismatch} and the real bundles.
     */
    @Test
    @DisplayName("every nameable query parameter is named by its own label in a type-mismatch refusal")
    void handlerNamesParametersByTheirOwnLabel() throws IOException {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(source);
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/x");
        List<String> unnamed = new ArrayList<>();
        for (String lang : LANGUAGES) {
            Locale locale = Locale.forLanguageTag(lang);
            LocaleContextHolder.setLocale(locale);
            for (String name : FieldLabelCoverageTest.nameableParameters()) {
                String label = label(new Context(name, Nature.TEXT, true, "param"), locale);
                MethodArgumentTypeMismatchException ex =
                    new MethodArgumentTypeMismatchException("x", java.util.UUID.class, name, null, null);
                Object body = handler.handleTypeMismatch(ex, request).getBody();
                String message = String.valueOf(((Map<?, ?>) body).get("message"));
                if (label == null || !message.contains(label)) {
                    unnamed.add(lang + ":" + name + " -> " + message);
                }
            }
        }
        assertThat(unnamed).as("a parameter refusal that does not use the parameter's own label (QA D1377)").isEmpty();
    }

    /** The label a refusal names this context by: a parameter's own key first, then the shared one. */
    private String label(Context context, Locale locale) {
        if (context.parameter()) {
            String own = source.getMessage(GlobalExceptionHandler.PARAMETER_LABEL_PREFIX + context.name(), null, null,
                locale);
            if (own != null && !own.isBlank()) {
                return own;
            }
        }
        String shared = source.getMessage("field.label." + context.name(), null, null, locale);
        return shared == null || shared.isBlank() ? null : shared;
    }

    enum Nature { TIME, NUMBER, FLAG, TEXT }

    record Context(String name, Nature nature, boolean parameter, String owner) {
        @Override
        public String toString() {
            return (parameter ? "?" + name + "= (" + owner + ")" : owner + "." + name) + " [" + nature + "]";
        }
    }

    private static Nature natureOf(Class<?> type) {
        if (java.time.temporal.Temporal.class.isAssignableFrom(type)) {
            return Nature.TIME;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Nature.FLAG;
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            return Nature.NUMBER;
        }
        return Nature.TEXT;
    }

    /** The element type of a collection / optional, or the type itself. */
    private static Class<?> valueType(Class<?> raw, Type generic) {
        if ((Collection.class.isAssignableFrom(raw) || raw == java.util.Optional.class)
                && generic instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> e) {
            return e;
        }
        return raw;
    }

    /** Every request-body field (by declaring DTO) and every query parameter (by controller). */
    static List<Context> contexts() throws IOException {
        List<Context> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Method m : controllerMethods()) {
            for (Parameter p : m.getParameters()) {
                RequestParam rp = p.getAnnotation(RequestParam.class);
                if (rp != null && !Map.class.isAssignableFrom(p.getType())) {
                    String name = rp.name().isEmpty() ? rp.value() : rp.name();
                    out.add(new Context(name.isEmpty() ? p.getName() : name,
                        natureOf(valueType(p.getType(), p.getParameterizedType())), true,
                        m.getDeclaringClass().getSimpleName()));
                }
                if (p.isAnnotationPresent(RequestBody.class)) {
                    bodyContexts(p.getType(), seen, out);
                    if (p.getParameterizedType() instanceof ParameterizedType pt) {
                        for (Type argument : pt.getActualTypeArguments()) {
                            if (argument instanceof Class<?> element) {
                                bodyContexts(element, seen, out);
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void bodyContexts(Class<?> type, Set<Class<?>> seen, List<Context> out) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || type.isInterface() || !seen.add(type)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }
                com.fasterxml.jackson.annotation.JsonProperty jp =
                    f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                String name = jp != null && !jp.value().isEmpty() ? jp.value() : f.getName();
                out.add(new Context(name, natureOf(valueType(f.getType(), f.getGenericType())), false,
                    c.getSimpleName()));
                bodyContexts(f.getType(), seen, out);
                if (f.getGenericType() instanceof ParameterizedType p) {
                    for (Type argument : p.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            bodyContexts(element, seen, out);
                        }
                    }
                }
            }
        }
    }

    private static List<Method> controllerMethods() throws IOException {
        List<Method> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Paths.get("src/main/java").relativize(f).toString()
                    .replace(java.io.File.separatorChar, '/').replace(".java", "");
                try {
                    out.addAll(List.of(Class.forName(rel.replace('/', '.')).getDeclaredMethods()));
                } catch (ClassNotFoundException e) {
                    // not a top-level class of that name
                }
            }
        }
        return out;
    }
}
