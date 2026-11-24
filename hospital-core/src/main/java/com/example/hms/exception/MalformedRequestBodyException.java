package com.example.hms.exception;

import jakarta.validation.ConstraintViolation;

import java.io.Serial;
import java.util.List;

/**
 * A request body with malformed values (QA D1349, round 30): every malformed
 * value's JSON path, in reading order, and the bean-validation refusals of the
 * rest of the body, so one answer names every field to correct.
 * {@link GlobalExceptionHandler} turns it into the usual 400.
 */
public class MalformedRequestBodyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** One bean-validation refusal of the body, with the JSON prefix of its element ("[0]" in a list body). */
    public record BodyViolation(String prefix, Class<?> beanType, ConstraintViolation<?> violation) {
    }

    private final transient List<String> malformedPaths;
    private final transient List<BodyViolation> violations;
    private final transient List<String> notAcceptedPaths;
    private final transient Class<?> bodyType;

    public MalformedRequestBodyException(List<String> malformedPaths, List<BodyViolation> violations) {
        this(malformedPaths, List.of(), violations);
    }

    /** QA D1672: with the properties a {@code @RejectsUnknownFields} request does not take. */
    public MalformedRequestBodyException(List<String> malformedPaths, List<String> notAcceptedPaths,
                                         List<BodyViolation> violations) {
        this(malformedPaths, notAcceptedPaths, violations, null);
    }

    /** QA D3753: with the class the body was read as, so a search's refusal names the criteria it accepts. */
    public MalformedRequestBodyException(List<String> malformedPaths, List<String> notAcceptedPaths,
                                         List<BodyViolation> violations, Class<?> bodyType) {
        super("malformed request body: " + malformedPaths + ", not accepted: " + notAcceptedPaths);
        this.malformedPaths = List.copyOf(malformedPaths);
        this.notAcceptedPaths = List.copyOf(notAcceptedPaths);
        this.violations = List.copyOf(violations);
        this.bodyType = bodyType;
    }

    /** The class the body was read as; null when unknown. */
    public Class<?> getBodyType() {
        return bodyType;
    }

    /** The properties the request does not take (a change that would have been dropped). */
    public List<String> getNotAcceptedPaths() {
        return notAcceptedPaths;
    }

    public List<String> getMalformedPaths() {
        return malformedPaths;
    }

    public List<BodyViolation> getViolations() {
        return violations;
    }
}
