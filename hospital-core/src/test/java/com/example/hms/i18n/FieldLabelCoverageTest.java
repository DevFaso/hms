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
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
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
                if (hasDefaultMessageConstraint(f)) {
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

    private static boolean parsed(Class<?> type) {
        return type == UUID.class || type.isEnum() || Temporal.class.isAssignableFrom(type)
            || Number.class.isAssignableFrom(type) || type == Boolean.class
            || (type.isPrimitive() && type != boolean.class && type != char.class);
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

    private static Set<Class<?>> requestBodies() throws IOException {
        Set<Class<?>> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Paths.get("src/main/java").relativize(f).toString().replace('\\', '/').replace(".java", "");
                Class<?> controller;
                try {
                    controller = Class.forName(rel.replace('/', '.'));
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Method m : controller.getDeclaredMethods()) {
                    for (Parameter p : m.getParameters()) {
                        if (p.isAnnotationPresent(RequestBody.class)) {
                            out.add(p.getType());
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
