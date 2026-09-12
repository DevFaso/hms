package com.example.hms.controller;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code @PreAuthorize} guards of a controller, read off the annotations
 * so a role test asserts the contract itself rather than a {@code @WebMvcTest}
 * slice (which does not run method security). Shared by the E9 #67 slices.
 */
final class GuardIndex {

    private static final String ROLE = "HOSPITAL_ADMIN";

    private GuardIndex() {
    }

    /** Every guard on the class keyed "VERB path"; a class-level guard is keyed "CLASS". */
    static Map<String, String> guardsOf(Class<?> controller) {
        Map<String, String> guards = new LinkedHashMap<>();
        PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
        if (onClass != null) {
            guards.put("CLASS", onClass.value());
        }
        for (Method method : controller.getDeclaredMethods()) {
            PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
            if (pre == null) {
                continue;
            }
            guards.put(verbAndPath(method), pre.value());
        }
        return guards;
    }

    private static String verbAndPath(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
            return "? " + method.getName();
        }
        String verb = mapping.method().length > 0 ? mapping.method()[0].name() : "?";
        String path = mapping.path().length > 0 ? mapping.path()[0] : "";
        return verb + " " + path;
    }

    private static String pathOf(String where) {
        int space = where.indexOf(' ');
        return space < 0 ? "" : where.substring(space + 1);
    }

    /** Every guard on the controller refuses HOSPITAL_ADMIN, except those whose path starts with a kept prefix. */
    static void assertOffChart(Class<?> controller, String... keptPathPrefixes) {
        Map<String, String> guards = guardsOf(controller);
        assertThat(guards).as("%s has guards", controller.getSimpleName()).isNotEmpty();
        guards.forEach((where, guard) -> {
            String path = pathOf(where);
            boolean kept = Arrays.stream(keptPathPrefixes).anyMatch(path::startsWith);
            if (!kept) {
                assertThat(guard).as("%s %s", controller.getSimpleName(), where).doesNotContain(ROLE);
            }
        });
    }

    /** The named guard refuses HOSPITAL_ADMIN. */
    static void assertRefuses(Class<?> controller, String where) {
        Map<String, String> guards = guardsOf(controller);
        assertThat(guards).as("%s declares %s", controller.getSimpleName(), where).containsKey(where);
        assertThat(guards.get(where)).as("%s %s", controller.getSimpleName(), where).doesNotContain(ROLE);
    }

    /** The named guard still admits HOSPITAL_ADMIN: the cut did not over-reach. */
    static void assertKept(Class<?> controller, String where) {
        Map<String, String> guards = guardsOf(controller);
        assertThat(guards).as("%s declares %s", controller.getSimpleName(), where).containsKey(where);
        assertThat(guards.get(where)).as("%s %s keeps HOSPITAL_ADMIN", controller.getSimpleName(), where)
            .contains(ROLE);
    }
}
