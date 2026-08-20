package com.example.hms.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Shared response-body field names — kept as constants for Sonar S1192. */
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_STATUS    = "status";
    private static final String FIELD_ERROR     = "error";
    private static final String FIELD_MESSAGE   = "message";
    private static final String FIELD_PATH      = "path";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Object> handleConflictException(ConflictException ex, WebRequest request) {
        // Convention: message may be prefixed with "field:" to identify the offending field.
        // e.g. "email:Email 'x@y.com' is already registered."
        String raw = ex.getMessage();
        String field = null;
        String message = raw;
        if (raw != null && raw.contains(":")) {
            int idx = raw.indexOf(':');
            field = raw.substring(0, idx).trim();
            message = raw.substring(idx + 1).trim();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, HttpStatus.CONFLICT.value());
        body.put(FIELD_ERROR, "Conflict");
        body.put(FIELD_MESSAGE, message);
        if (field != null) body.put("field", field);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(com.example.hms.cdshooks.CdsCriticalBlockException.class)
    public ResponseEntity<Object> handleCdsCriticalBlock(
            com.example.hms.cdshooks.CdsCriticalBlockException ex, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(FIELD_ERROR, "CDS Critical Advisory");
        body.put(FIELD_MESSAGE, ex.getMessage());
        body.put("cdsAdvisories", ex.getCards());
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleIllegalStateException(IllegalStateException ex, WebRequest request) {
        // Audit failures should never reach here (they are swallowed in the service layer),
        // but as a safety net, treat them as internal errors rather than client errors.
        if (ex.getMessage() != null && ex.getMessage().contains("audit")) {
            log.error("Unexpected audit exception leaked to controller layer: {}", ex.getMessage(), ex);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "An internal error occurred. The operation may have succeeded.", request);
        }
        log.warn("Illegal state (business rule violation): {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Object> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        ex.getMostSpecificCause();
        String message = ex.getMostSpecificCause().getMessage();
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Catches Jackson deserialisation failures (unknown enum values, malformed
     * JSON, type-coercion errors) and surfaces them as 400 with the original
     * Jackson message — far more actionable than the generic 500 the catch-all
     * RuntimeException handler used to produce. Triggered on dev when the
     * pharmacy-registry frontend posted {@code "pharmacyType":"COMMUNITY"}
     * against the {@code PharmacyType} enum whose accepted values are
     * {@code COMMUNITY_PHARMACY / PARTNER_PHARMACY / HOSPITAL_DISPENSARY}.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        // Jackson exception messages embed the offending field path and the
        // accepted values for enums; surface them so the caller can self-
        // diagnose. The cause's message is shorter and cleaner than the
        // outer JsonMappingException stack-trace prefix, but it can be null
        // even when the cause itself is non-null (rare Jackson paths build a
        // cause with no detail message) — fall through to the outer message
        // and then to a fixed default so the response is never
        // "Malformed request body: null".
        String detail = resolveJacksonDetail(ex);
        String message = detail.isEmpty()
            ? "Malformed request body."
            : "Malformed request body: " + detail;
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    private static String resolveJacksonDetail(HttpMessageNotReadableException ex) {
        // getMostSpecificCause() falls back to the throwable itself when no
        // cause is chained, so it is documented non-null. Its detail message,
        // however, can be null on edge-case Jackson paths.
        String causeMessage = ex.getMostSpecificCause().getMessage();
        if (causeMessage != null && !causeMessage.isBlank()) {
            return causeMessage;
        }
        String outer = ex.getMessage();
        return outer != null && !outer.isBlank() ? outer : "";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationException(MethodArgumentNotValidException ex, WebRequest request) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                    FieldError::getField,
                        error -> error.getDefaultMessage(),
                        (existing, replacement) -> existing // in case of duplicates
                ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(FIELD_ERROR, "Validation Failed");
        body.put(FIELD_MESSAGE, "Validation errors in request");
        body.put("fieldErrors", fieldErrors);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        Map<String, String> violations = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (existing, replacement) -> existing
                ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, HttpStatus.BAD_REQUEST.value());
        body.put(FIELD_ERROR, "Validation Failed");
        body.put(FIELD_MESSAGE, "Constraint violation in request parameters");
        body.put("fieldErrors", violations);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({EntityNotFoundException.class, JpaObjectRetrievalFailureException.class})
    @SuppressWarnings("java:S2629")
    public ResponseEntity<Object> handleEntityNotFound(RuntimeException ex, WebRequest request) {
        // A Hibernate proxy tried to load an entity that was deleted from the DB (dangling FK).
        // Log the full detail for ops investigation but return a sanitised message to the caller.
        log.error("Data integrity issue — referenced entity not found at path {}: {}",
            request.getDescription(false), ex.getMessage(), ex);
        return buildErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "A referenced record could not be found. This may indicate a data integrity issue. " +
            "Please contact support if this persists.",
            request);
    }

    /**
     * Parameter-binding failures (e.g. an enum query param the deployed jar
     * does not know, a non-UUID path variable) are client errors. Without
     * this handler they fall through to the RuntimeException catch-all below
     * and surface as 500 — exactly what happened when a stale backend
     * received {@code sources=PLATFORM_CONFIG} on the aggregated audit search.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "value";
        String message = "Invalid value '" + ex.getValue() + "' for parameter '"
                + ex.getName() + "' (expected " + requiredType + ")";
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(RuntimeException.class)
    @SuppressWarnings("java:S2629")
    public ResponseEntity<Object> handleRuntimeException(RuntimeException ex, WebRequest request) {
    // Log full stack to aid debugging of 500s (safe: message already generic in response)
    log.error("Unhandled runtime exception at path {}: {}", request.getDescription(false), ex.getMessage(), ex);
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred: " + ex.getMessage(), request);
    }

    private ResponseEntity<Object> buildErrorResponse(HttpStatus status, String message, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, status.value());
        body.put(FIELD_ERROR, status.getReasonPhrase());
        body.put(FIELD_MESSAGE, message);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest req) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access denied", req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleMaxUploadSize(MaxUploadSizeExceededException ex, WebRequest request) {
        log.warn("File upload too large: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "File size exceeds the maximum allowed limit of 5MB", request);
    }


}
