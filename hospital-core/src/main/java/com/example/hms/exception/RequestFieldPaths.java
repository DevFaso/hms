package com.example.hms.exception;

import com.example.hms.validation.ReportedOn;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * Turns a bean-validation property path into the path of the request JSON,
 * so each {@code fieldErrors} key is a property the form sent (QA D1317 /
 * D1338, round 29). Bean validation names the Java property: a field renamed
 * by {@code @JsonProperty("testName")} was refused as {@code name}, and a
 * cross-field {@code @AssertTrue} getter by its own name. Each segment is
 * mapped on the class it belongs to: a field's JSON name, a rule's
 * {@link ReportedOn} target; list indexes and unknown segments pass unchanged.
 */
final class RequestFieldPaths {

    private RequestFieldPaths() {
    }

    static String toJson(Class<?> root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        Class<?> current = root;
        for (String segment : path.split("\\.")) {
            int bracket = segment.indexOf('[');
            String name = bracket >= 0 ? segment.substring(0, bracket) : segment;
            String index = bracket >= 0 ? segment.substring(bracket) : "";
            Field field = current != null && !name.isEmpty() ? findField(current, name) : null;
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(jsonSegment(current, name, field)).append(index);
            current = field != null ? valueType(field, !index.isEmpty()) : null;
        }
        return out.toString();
    }

    /**
     * The JSON name of one segment on its class: a field's {@code @JsonProperty},
     * a rule's {@link ReportedOn} target, else the name unchanged (Sonar S3776,
     * round 30: split out of {@link #toJson}).
     */
    private static String jsonSegment(Class<?> owner, String name, Field field) {
        if (field != null) {
            JsonProperty json = field.getAnnotation(JsonProperty.class);
            return json != null && !json.value().isEmpty() ? json.value() : name;
        }
        if (owner == null || name.isEmpty()) {
            return name;
        }
        ReportedOn reportedOn = findRule(owner, name);
        return reportedOn != null ? reportedOn.value() : name;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException absent) {
                // look in the superclass
            }
        }
        return null;
    }

    private static ReportedOn findRule(Class<?> type, String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (String getter : new String[] {"is" + suffix, "get" + suffix}) {
            try {
                Method method = type.getMethod(getter);
                ReportedOn reportedOn = method.getAnnotation(ReportedOn.class);
                if (reportedOn != null) {
                    return reportedOn;
                }
            } catch (NoSuchMethodException absent) {
                // try the other getter form
            }
        }
        return null;
    }

    /** The class the next segment belongs to: the field's type, or its element type when indexed. */
    private static Class<?> valueType(Field field, boolean indexed) {
        Class<?> type = field.getType();
        if (!indexed) {
            return type;
        }
        if (type.isArray()) {
            return type.getComponentType();
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType p) {
                Type[] args = p.getActualTypeArguments();
                if (args[args.length - 1] instanceof Class<?> element) {
                    return element;
                }
            }
        }
        return null;
    }
}
