package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1268 (round 26): a default constraint refusal named the field by its
 * raw property inside a French or Spanish sentence ("fullName : ne doit pas
 * être vide", "encounterType : ne doit pas être nul", "« dueDate »"). The
 * handler takes the label from {@code field.label.<property>} and falls back
 * to the property name when none exists - and only fifteen existed.
 *
 * <p>Every request-body field the handler can name gets a label in English,
 * French and Spanish: a field whose constraint keeps the default message
 * (the handler prefixes it with the label), and a field Jackson parses (a
 * UUID, enum, date, number or flag - the "value not valid for {field}"
 * refusal). A field whose every constraint carries its own sentence, and a
 * free-text field without a default-message constraint, never reach the label.
 */
@DisplayName("D1268: every request field a refusal can name has a label in EN, FR and ES")
class FieldLabelCoverageTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");
    private static final List<String> LOCALES = List.of("_en", "_fr", "_es");
    private static final List<String> DEFAULT_TEMPLATES =
        List.of("{jakarta.validation.constraints.", "{org.hibernate.validator.constraints.");

    @Test
    @DisplayName("each nameable field has field.label.<name> in every language")
    void everyNameableFieldHasALabel() throws IOException {
        Set<String> names = nameableFields();
        assertThat(names).as("nameable request fields found").hasSizeGreaterThan(100);
        Map<String, List<String>> missing = new TreeMap<>();
        for (String suffix : LOCALES) {
            Properties labels = labels(suffix);
            for (String name : names) {
                String label = labels.getProperty("field.label." + name);
                if (label == null || label.isBlank()) {
                    missing.computeIfAbsent(name, k -> new ArrayList<>()).add(suffix.substring(1));
                }
            }
        }
        assertThat(missing)
            .as("a refusal would name these fields by their raw property in the sentence; add field.label.<name>"
                + " to messages-common (QA D1268)")
            .isEmpty();
    }

    /**
     * QA D1350 (round 30): "Une valeur obligatoire est manquante : « slotId »",
     * "« accepted »", "« from »": a query parameter is named by the missing-value
     * and invalid-value refusals, and those printed its raw name when no label
     * existed. Every {@code @RequestParam} a refusal can name - a required one
     * (missing) and a parsed one (unparsable) - has a label in every language.
     */
    @Test
    @DisplayName("D1350: each query parameter a refusal can name has field.label.<name> in every language")
    void everyNameableParameterHasALabel() throws IOException {
        Set<String> names = nameableParameters();
        assertThat(names).as("nameable query parameters found").hasSizeGreaterThan(50);
        Map<String, List<String>> missing = new TreeMap<>();
        for (String suffix : LOCALES) {
            Properties labels = labels(suffix);
            for (String name : names) {
                // QA D1377: a parameter's own key is read first.
                String label = labels.getProperty("field.label.param." + name, labels.getProperty("field.label." + name));
                if (label == null || label.isBlank()) {
                    missing.computeIfAbsent(name, k -> new ArrayList<>()).add(suffix.substring(1));
                }
            }
        }
        assertThat(missing)
            .as("a missing or unparsable query parameter with no label is refused naming nothing;"
                + " add field.label.<name> to messages-common (QA D1350)")
            .isEmpty();
    }

    /** The names of every {@code @RequestParam} that is required or parsed. */
    static Set<String> nameableParameters() throws IOException {
        Set<String> out = new TreeSet<>();
        for (Method m : controllerMethods()) {
            for (Parameter p : m.getParameters()) {
                org.springframework.web.bind.annotation.RequestParam rp =
                    p.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
                if (rp == null || java.util.Map.class.isAssignableFrom(p.getType())
                        || org.springframework.web.multipart.MultipartFile.class.isAssignableFrom(p.getType())) {
                    continue;
                }
                boolean required = rp.required()
                    && org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())
                    && p.getType() != java.util.Optional.class;
                Class<?> type = elementOf(p);
                if (required || isParsed(type)) {
                    String name = rp.name().isEmpty() ? rp.value() : rp.name();
                    out.add(name.isEmpty() ? p.getName() : name);
                }
            }
        }
        return out;
    }

    /** The parameter's type, or the element type of a list / optional parameter. */
    private static Class<?> elementOf(Parameter p) {
        if ((java.util.Collection.class.isAssignableFrom(p.getType()) || p.getType() == java.util.Optional.class)
                && p.getParameterizedType() instanceof ParameterizedType pt
                && pt.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return p.getType();
    }

    @Test
    @DisplayName("no French or Spanish label is the camel-case property name itself")
    void labelsAreTranslated() throws IOException {
        List<String> raw = new ArrayList<>();
        for (String suffix : List.of("_fr", "_es")) {
            Properties labels = labels(suffix);
            for (String key : labels.stringPropertyNames()) {
                String property = key.substring("field.label.".length());
                // "fullName" left as its own label; a one-word homograph ("code", "type") is a real word.
                if (key.startsWith("field.label.") && !property.equals(property.toLowerCase(java.util.Locale.ROOT))
                        && labels.getProperty(key).equals(property)) {
                    raw.add(suffix + ": " + key);
                }
            }
        }
        assertThat(raw).as("a label that is the property name itself (QA D1268)").isEmpty();
    }

    /**
     * QA D1374 (round 31): {@code ?from=abc} was refused as « du » and
     * {@code ?to=xyz} as « destinataire » - a preposition, and a word borrowed
     * from a chat recipient - so the reader could not tell which field was
     * wrong. A label names a field: never a bare preposition or article; and
     * the query parameter {@code ?to=}, a period's end, never reads as a
     * recipient.
     *
     * <p>QA D1377 (round 32): this test said "no request body has a recipient
     * called {@code to}" and asserted {@code field.label.to} never read as one
     * - false: the invoice e-mail body's {@code to} IS its recipient, and the
     * assertion locked the regression in. The parameter now has its own key
     * ({@code field.label.param.to}); the body field keeps "recipient".
     */
    @Test
    @DisplayName("D1374/D1377: no label is a bare preposition; ?to= is a period's end, the e-mail body's to its recipient")
    void labelsNameAField() throws IOException {
        Set<String> notAName = Set.of("du", "de", "des", "la", "le", "les", "au", "à", "a", "en", "par", "pour",
            "from", "to", "by", "of", "the", "for", "since", "until", "depuis", "desde", "hasta", "del", "el", "los", "las", "para", "por");
        List<String> bare = new ArrayList<>();
        for (String suffix : List.of("", "_en", "_fr", "_es")) {
            Properties labels = labels(suffix);
            for (String key : labels.stringPropertyNames()) {
                if (key.startsWith("field.label.")
                        && notAName.contains(labels.getProperty(key).trim().toLowerCase(java.util.Locale.ROOT))) {
                    bare.add(suffix + ": " + key + "=" + labels.getProperty(key));
                }
            }
            assertThat(labels.getProperty("field.label.param.to")).as(suffix + " ?to=")
                .doesNotContain("destinat", "recipient");
            assertThat(labels.getProperty("field.label.to")).as(suffix + " EmailInvoiceRequest.to")
                .containsAnyOf("destinat", "recipient");
        }
        assertThat(bare).as("a label that names no field (QA D1374)").isEmpty();
    }

    /**
     * QA D1313 (round 29, regression of D1312): the handler's "no label" test
     * was {@code label.equals(field)}, so every real label that spells its key
     * (EN relationship, date, amount; FR permissions) lost its name. Every
     * request field a refusal can name is named by its own label, in EN, FR
     * and ES, through the real handler and the real bundles.
     */
    @Test
    @DisplayName("D1313: every nameable field is named by its label in a malformed-value refusal, in every language")
    void everyNameableFieldIsNamedInTheRefusal() throws IOException {
        org.springframework.context.MessageSource source = TestMessageSources.bundles();
        com.example.hms.exception.GlobalExceptionHandler handler = new com.example.hms.exception.GlobalExceptionHandler();
        handler.setMessageSource(source);
        org.springframework.web.context.request.WebRequest request =
            org.mockito.Mockito.mock(org.springframework.web.context.request.WebRequest.class);
        org.mockito.Mockito.when(request.getDescription(false)).thenReturn("uri=/api/x");
        List<String> unnamed = new ArrayList<>();
        try {
            for (String lang : List.of("en", "fr", "es")) {
                java.util.Locale locale = java.util.Locale.forLanguageTag(lang);
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                for (String field : nameableFields()) {
                    String label = source.getMessage("field.label." + field, null, null, locale);
                    Object body = handler.handleHttpMessageNotReadable(malformed(field), request).getBody();
                    String message = String.valueOf(((Map<?, ?>) body).get("message"));
                    if (label == null || !message.contains(label)) {
                        unnamed.add(lang + ":" + field + " -> " + message);
                    }
                }
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        assertThat(unnamed).as("refusals that do not name the field by its label (QA D1313)").isEmpty();
    }

    private static org.springframework.http.converter.HttpMessageNotReadableException malformed(String property) {
        var cause = tools.jackson.databind.exc.InvalidFormatException.from(
            (tools.jackson.core.JsonParser) null, "bad value", "x", Object.class);
        cause.prependPath(new tools.jackson.core.JacksonException.Reference(null, property));
        return new org.springframework.http.converter.HttpMessageNotReadableException(
            "x", cause, new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));
    }

    /** The JSON names of every request-body field a refusal can name. */
    static Set<String> nameableFields() throws IOException {
        Set<String> out = new TreeSet<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> body : requestBodies()) {
            collect(body, seen, out);
        }
        return out;
    }

    private static void collect(Class<?> type, Set<Class<?>> seen, Set<String> out) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || type.isInterface() || !seen.add(type)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || f.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) {
                    continue;
                }
                // QA D1312 (round 28): a field Jackson parses (enum, UUID, date,
                // number, flag) is named by the "value not valid for {field}"
                // refusal whatever its constraints; copayMethod was not read.
                if (hasDefaultMessageConstraint(f) || isParsed(f.getType())) {
                    out.add(jsonName(f));
                }
                collect(f.getType(), seen, out);
                if (f.getGenericType() instanceof ParameterizedType p) {
                    for (Type argument : p.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            collect(element, seen, out);
                        }
                    }
                }
            }
        }
    }

    /**
     * A type Jackson parses from what a form sends (a select's enum, a
     * picker's UUID, a typed date or number, a flag), so a malformed value is
     * refused with the field's label. QA D1350 (round 30): flags were left out
     * ("a checkbox sends true or false"), and a malformed {@code highRisk} or
     * {@code flexibilityAcknowledgment} was refused naming no field at all.
     */
    static boolean isParsed(Class<?> type) {
        return type.isEnum() || type.isPrimitive() || type == Boolean.class || Number.class.isAssignableFrom(type)
            || type == java.util.UUID.class || java.time.temporal.Temporal.class.isAssignableFrom(type);
    }

    private static boolean hasDefaultMessageConstraint(Field field) {
        for (Annotation a : field.getAnnotations()) {
            if (!a.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)) {
                continue;
            }
            try {
                Method message = a.annotationType().getMethod("message");
                String template = String.valueOf(message.invoke(a));
                if (DEFAULT_TEMPLATES.stream().anyMatch(template::startsWith)) {
                    return true;
                }
            } catch (ReflectiveOperationException e) {
                return true;
            }
        }
        return false;
    }

    private static String jsonName(Field field) {
        com.fasterxml.jackson.annotation.JsonProperty p =
            field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        return p != null && !p.value().isEmpty() ? p.value() : field.getName();
    }

    private static List<Method> controllerMethods() throws IOException {
        List<Method> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Paths.get("src/main/java").relativize(f).toString().replace('\\', '/').replace(".java", "");
                try {
                    out.addAll(List.of(Class.forName(rel.replace('/', '.')).getDeclaredMethods()));
                } catch (ClassNotFoundException e) {
                    // not a top-level class of that name
                }
            }
        }
        return out;
    }

    private static Set<Class<?>> requestBodies() throws IOException {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Method m : controllerMethods()) {
            for (Parameter p : m.getParameters()) {
                if (!p.isAnnotationPresent(RequestBody.class)) {
                    continue;
                }
                out.add(p.getType());
                // QA D1350 (round 30): the element of a list body
                // (notification preferences) was never read.
                if (p.getParameterizedType() instanceof ParameterizedType pt) {
                    for (Type argument : pt.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            out.add(element);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Properties labels(String suffix) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = FieldLabelCoverageTest.class.getResourceAsStream("/i18n/messages-common" + suffix + ".properties")) {
            assertThat(in).as("messages-common" + suffix).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
