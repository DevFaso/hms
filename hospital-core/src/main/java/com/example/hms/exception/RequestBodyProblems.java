package com.example.hms.exception;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The malformed values of the request body being read (QA D1349, round 30).
 *
 * <p>Jackson stops at the first value it cannot convert, so a body with two
 * malformed values, or a malformed value beside blank required fields, named
 * only the first. While a request body is read, the problem handler of
 * {@code RequestBodyDefaultsModule} records each malformed value's JSON path
 * here and reads it as absent, and {@code MalformedRequestBodyAdvice} then
 * refuses the request naming every one of them with the body's bean
 * validation refusals. A body with a recorded value is ALWAYS refused: a
 * value read as absent never reaches a controller.
 *
 * <p>The collector lives in the request's attributes, opened just before the
 * body is read and closed just after it, so any other JSON the application
 * reads (a stored column, a partner's response, a body read outside MVC) keeps
 * Jackson's strict failure.
 */
public final class RequestBodyProblems {

    private static final String ATTRIBUTE = RequestBodyProblems.class.getName();

    private RequestBodyProblems() {
    }

    /** Opens the collector for the body about to be read. */
    public static void begin() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTRIBUTE, new Collector(), RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** True while a request body is being read. */
    public static boolean collecting() {
        return current() != null;
    }

    /** Records a malformed value; false when no request body is being read (the caller keeps the strict failure). */
    public static boolean record(String path) {
        Collector collector = current();
        if (collector == null) {
            return false;
        }
        collector.paths.add(path);
        return true;
    }

    /** Closes the collector and returns the malformed paths in the order they were read. */
    public static List<String> finish() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Collector collector = current();
        if (attributes != null) {
            attributes.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        }
        return collector == null ? List.of() : new ArrayList<>(collector.paths);
    }

    private static Collector current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof Collector collector ? collector : null;
    }

    /** The paths of one body, each once, in reading order. */
    private static final class Collector {
        private final Set<String> paths = new LinkedHashSet<>();
    }
}
