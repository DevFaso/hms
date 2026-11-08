package com.example.hms.architecture;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;

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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1351 (round 30): {@code POST /isolation/precautions} and
 * {@code POST /transfers} with a blank {@code reason} named the other missing
 * fields and the reason only on the next request: the field was
 * {@code @NotNull String}, so {@code "   "} passed bean validation and a
 * service check refused it one request later.
 *
 * <p>Every text field of every request body (each class a controller binds
 * with {@code @RequestBody}, its nested classes and list elements) that is
 * declared required with {@code @NotNull} is {@code @NotBlank} instead:
 * bean validation refuses a blank and a missing value alike, by name, with
 * the body's other fields. The behavioural half is phase 6 of
 * {@code WriteEndpointRequiredFieldSweepIT}.
 */
@DisplayName("D1351: a required text field of a request body refuses a blank value")
class NotNullTextRequestFieldGuardTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    /**
     * "Class#field" of a {@code @NotNull String} that legitimately accepts blank,
     * each with why. Stale entries fail.
     */
    private static final Map<String, String> BLANK_ACCEPTED = Map.of();

    @Test
    @DisplayName("no request text field is @NotNull without @NotBlank")
    void requiredTextFieldsRefuseBlank() throws IOException {
        Set<String> found = new TreeSet<>();
        Set<Class<?>> bodies = requestBodies();
        assertThat(bodies).as("request bodies found").hasSizeGreaterThan(300);
        Set<Class<?>> seen = new LinkedHashSet<>();
        for (Class<?> body : bodies) {
            collect(body, seen, found);
        }
        Set<String> stale = new TreeSet<>(BLANK_ACCEPTED.keySet());
        stale.removeAll(found);
        found.removeAll(BLANK_ACCEPTED.keySet());
        assertThat(stale).as("allowlist entries that are no longer @NotNull String (remove them)").isEmpty();
        assertThat(found)
            .as("@NotNull String request fields: a blank passes bean validation and is named one request later"
                + " (QA D1351); declare @NotBlank (same message)")
            .isEmpty();
    }

    private static void collect(Class<?> type, Set<Class<?>> seen, Set<String> found) {
        if (!type.getName().startsWith("com.example.hms.") || type.isEnum() || type.isInterface() || !seen.add(type)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() == String.class && f.isAnnotationPresent(NotNull.class)
                        && !f.isAnnotationPresent(NotBlank.class)) {
                    found.add(c.getName().substring(c.getPackageName().length() + 1) + "#" + f.getName());
                }
                collect(f.getType(), seen, found);
                if (f.getGenericType() instanceof ParameterizedType p) {
                    for (Type argument : p.getActualTypeArguments()) {
                        if (argument instanceof Class<?> element) {
                            collect(element, seen, found);
                        }
                    }
                }
            }
        }
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
                            if (p.getParameterizedType() instanceof ParameterizedType pt) {
                                for (Type argument : pt.getActualTypeArguments()) {
                                    if (argument instanceof Class<?> element) {
                                        out.add(element);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }
}
