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

    public MalformedRequestBodyException(List<String> malformedPaths, List<BodyViolation> violations) {
        super("malformed request body: " + malformedPaths);
        this.malformedPaths = List.copyOf(malformedPaths);
        this.violations = List.copyOf(violations);
    }

    public List<String> getMalformedPaths() {
        return malformedPaths;
    }

    public List<BodyViolation> getViolations() {
        return violations;
    }
}
