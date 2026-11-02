package com.example.hms.exception;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final String FIELD_ERRORS = "fieldErrors";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Shared response-body field names — kept as constants for Sonar S1192. */
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_STATUS    = "status";
    private static final String FIELD_ERROR     = "error";
    private static final String FIELD_MESSAGE   = "message";
    private static final String FIELD_PATH      = "path";
    private static final String FIELD_CODE      = "code";
    private static final String FIELD_FIELD     = "field";

    /** Machine-readable codes the portal maps onto its own translated text (D53). */
    static final String CODE_MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String CODE_INVALID_PARAMETER = "INVALID_PARAMETER";
    static final String CODE_UNEXPECTED_ERROR  = "UNEXPECTED_ERROR";
    static final String CODE_ACCESS_DENIED     = "ACCESS_DENIED";

    /**
     * Optional so the many tests that build the advice with {@code new} keep
     * working; each lookup has an English default when it is absent.
     */
    private MessageSource messageSource;

    @Autowired(required = false)
    public void setMessageSource(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** Resolve a user-facing message in the request's locale, falling back to the default text. */
    private String localized(String key, String defaultText, Object... args) {
        if (messageSource == null) {
            return java.text.MessageFormat.format(defaultText.replace("'", "''"), args);
        }
        return messageSource.getMessage(key, args, defaultText.replace("'", "''"), LocaleContextHolder.getLocale());
    }

    /**
     * A message a thrower authored. Many services throw IllegalArgument/
     * IllegalState/Conflict with a bundle KEY rather than a sentence; those
     * exceptions, unlike {@link BusinessException}, never resolve it, so the
     * key (or its English default) reached a French user (D174). A key is
     * resolved in the request locale here; free text passes unchanged.
     */
    private static String passThrough(String message) {
        return com.example.hms.utility.MessageUtil.resolveOrRaw(message);
    }

    /**
     * {@code ResponseStatusException} carries its own status and a reason the
     * controller wrote. It extends RuntimeException, so without this handler
     * the catch-all below turned every intended 400/403/409 into a 500.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null || status.is5xxServerError()) {
            return handleRuntimeException(ex, request);
        }
        String reason = ex.getReason();
        return buildErrorResponse(status,
            reason == null ? status.getReasonPhrase() : passThrough(reason), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFoundException(ResourceNotFoundException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * The {@code "field:"} prefix of a {@link ConflictException} message: a
     * field name (a lower-case identifier, dots and indexes allowed) touching
     * the colon, and the message touching it on the other side. QA D904: the text before ANY first colon was taken as a field
     * name, so a translated sentence ("Impossible de supprimer l'affectation :
     * elle est encore utilisée...", "Cannot delete assignment: ...") lost its
     * first half and reached the toast as a lower-case fragment. A sentence
     * does not match: it starts with a capital, or has a space or an
     * apostrophe before its colon (French also puts a space before it).
     * ConflictMessagePrefixTest checks every bundle message against this.
     */
    public static final Pattern CONFLICT_FIELD_PREFIX =
        Pattern.compile("^([a-z]\\w*+(?:\\.\\w++|\\[\\d++])*+):(?!\\s)");

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Object> handleConflictException(ConflictException ex, WebRequest request) {
        // Convention: message may be prefixed with "field:" to identify the offending field.
        // e.g. "email:Email 'x@y.com' is already registered."
        String raw = ex.getMessage();
        String field = null;
        String message = raw;
        Matcher prefix = raw == null ? null : CONFLICT_FIELD_PREFIX.matcher(raw);
        if (prefix != null && prefix.lookingAt()) {
            field = prefix.group(1);
            message = raw.substring(prefix.end()).trim();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now());
        body.put(FIELD_STATUS, HttpStatus.CONFLICT.value());
        body.put(FIELD_ERROR, "Conflict");
        body.put(FIELD_MESSAGE, passThrough(message));
        if (field != null) body.put(FIELD_FIELD, field);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }

    /**
     * Two writers met on the same row (G15 rule 1): a row lock could not be
     * taken or Postgres broke a deadlock ({@link PessimisticLockingFailureException},
     * {@code CannotAcquireLockException} included), or a {@code @Version}
     * check failed on flush ({@link OptimisticLockingFailureException},
     * {@code ObjectOptimisticLockingFailureException} included). Nothing was
     * written; reloading and retrying is the right answer, so 409 rather than
     * the 500 the RuntimeException catch-all gave. The detail stays in the
     * log: it can name tables and ids.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Object> handleConcurrentModification(RuntimeException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Concurrent modification at path {}: {}", request.getDescription(false),
                    ex.getClass().getSimpleName());
        }
        return buildErrorResponse(HttpStatus.CONFLICT,
                com.example.hms.utility.MessageUtil.resolve("concurrent.modification"), request);
    }

    @ExceptionHandler(PatientAlreadyRegisteredException.class)
    public ResponseEntity<Object> handlePatientAlreadyRegistered(PatientAlreadyRegisteredException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
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

    /**
     * QA D806/D809: a business rule about one field. The standard body, plus
     * {@code fieldErrors} naming that field (the bean-validation shape), so the
     * form can mark it; the reason is already translated.
     */
    @ExceptionHandler(FieldBusinessException.class)
    public ResponseEntity<Object> handleFieldBusinessException(FieldBusinessException ex, WebRequest request) {
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
        if (ex.getField() != null && ex.getMessage() != null) {
            body.put(FIELD_FIELD, ex.getField());
            body.put(FIELD_ERRORS, Map.of(ex.getField(), ex.getMessage()));
        }
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * A domain rule refused the request. Same status as {@link BusinessException}
     * (the two are the same kind of refusal); without this handler it fell
     * through to {@link #handleRuntimeException} and reached the client as a 500.
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Object> handleBusinessRuleException(BusinessRuleException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, passThrough(ex.getMessage()), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleIllegalStateException(IllegalStateException ex, WebRequest request) {
        // Audit failures should never reach here (they are swallowed in the service layer),
        // but as a safety net, treat them as internal errors rather than client errors.
        if (ex.getMessage() != null && ex.getMessage().contains("audit")) {
            log.error("Unexpected audit exception leaked to controller layer: {}", ex.getMessage(), ex);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    localized("error.internal.mayHaveSucceeded",
                        "An internal error occurred. The operation may have succeeded."), request);
        }
        log.warn("Illegal state (business rule violation): {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, passThrough(ex.getMessage()), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Object> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, passThrough(ex.getMessage()), request);
    }

    /**
     * A token that was valid a moment earlier in the same request and expired
     * (or failed to parse) on a later read: "sign in again", never a 500.
     */
    @ExceptionHandler(io.jsonwebtoken.JwtException.class)
    public ResponseEntity<Object> handleJwt(io.jsonwebtoken.JwtException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Token no longer valid at path {}", request.getDescription(false));
        }
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, localized("error.session.expired", "Session expired. Please log in again."), request);
    }

    /**
     * {@link UnauthorizedException} carries {@code @ResponseStatus(UNAUTHORIZED)},
     * but a {@code @ResponseStatus} only applies when no handler claims the
     * exception — and {@link #handleRuntimeException} claims every
     * {@code RuntimeException}. So a rejected MFA step-up on the emergency
     * controls, impersonation start or tenant lifecycle came back as a 500
     * reading "An unexpected error occurred: mfa_required: …", logged as an
     * unhandled exception, and shown to the super-admin as a server fault
     * rather than "your code was wrong". Found 2026-09-05 testing the
     * emergency controls on dev.
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> handleUnauthorized(UnauthorizedException ex, WebRequest request) {
        log.warn("Unauthorized at path {}: {}", request.getDescription(false), ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, passThrough(ex.getMessage()), request);
    }

    /**
     * Constraint violations from the database.
     *
     * <p>The driver's message is logged in full and <b>not</b> returned. It
     * used to be passed to the caller verbatim, which on a failed nurse-handoff
     * insert meant the response carried the schema name, the table name, every
     * column of the rejected row and the PATIENT'S UUID — clinical identifiers
     * handed to whoever made the request, over a message no user could act on.
     *
     * <p>What the caller gets instead names the constraint family in ordinary
     * words, which is as much as is safely actionable: a duplicate is worth
     * retrying differently, a missing reference is not. Anything more specific
     * is in the log, keyed by the same request path.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        String detail = ex.getMostSpecificCause().getMessage();
        log.error("Data integrity violation at path {}: {}",
            request.getDescription(false), detail, ex);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, describeIntegrityViolation(detail), request);
    }

    /**
     * Map a driver message onto a sanitised description. Matching is on the
     * constraint wording rather than on any identifier, so nothing from the
     * row or the schema can leak through this method.
     */
    private String describeIntegrityViolation(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unique") || lower.contains("duplicate key")) {
            return localized("error.integrity.duplicate", "That record already exists.");
        }
        if (lower.contains("foreign key")) {
            return localized("error.integrity.reference", "A referenced record does not exist or is still in use.");
        }
        if (lower.contains("not-null") || lower.contains("not null")) {
            return localized("error.integrity.required",
                "A required value is missing. Please contact support if this persists.");
        }
        return localized("error.integrity.constraint",
            "The request could not be saved because it violates a data constraint.");
    }

    /**
     * Jackson deserialisation failures (unknown enum values, malformed JSON,
     * type-coercion errors) are a 400 — but the parser's own message is never
     * returned (D53). It named Java types and the accepted enum constants
     * ("Cannot deserialize value of type `...PharmacyType` from String ...")
     * and the portal showed it verbatim in a toast. The detail goes to the
     * log; the caller gets a translated sentence naming at most the field,
     * plus {@code code=MALFORMED_REQUEST} for clients that translate locally.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Unreadable request body at path {}: {}",
                request.getDescription(false), ex.getMostSpecificCause().getClass().getSimpleName());
        }
        String field = resolveJacksonField(ex);
        String message = field == null
            ? localized("error.request.malformed",
                "The request could not be read. Check the values entered and try again.")
            : localized("error.request.invalidField",
                "The value entered for \"{0}\" is not valid.", fieldLabel(field));
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST, message, request);
        body.put(FIELD_CODE, CODE_MALFORMED_REQUEST);
        if (field != null) {
            body.put(FIELD_FIELD, field);
        }
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * QA D754: the caller-facing sentence named the raw JSON property
     * ("leaveType") in French and Spanish. The label comes from
     * {@code field.label.<last property>} in the request locale; a field with
     * no label keeps its path. The {@code field} entry of the body stays the
     * raw path so clients can still map it to an input.
     */
    private String fieldLabel(String path) {
        if (path == null || messageSource == null) {
            return path;
        }
        String leaf = path.replaceAll("\\[\\d+]", "");
        int dot = leaf.lastIndexOf('.');
        if (dot >= 0) {
            leaf = leaf.substring(dot + 1);
        }
        if (leaf.isEmpty()) {
            return path;
        }
        String label = messageSource.getMessage("field.label." + leaf, null, null, LocaleContextHolder.getLocale());
        return label == null || label.isBlank() ? path : label;
    }

    /** The JSON property path of the failure ("items[0].dose" style), or null when Jackson gave none. */
    private static String resolveJacksonField(HttpMessageNotReadableException ex) {
        if (!(ex.getMostSpecificCause() instanceof tools.jackson.core.JacksonException jackson)) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (tools.jackson.core.JacksonException.Reference ref : jackson.getPath()) {
            String name = ref.getPropertyName();
            if (name != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(name);
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
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
        // QA D1037 / D984: the message says what to fix. It was the generic
        // "the request parameters are not valid" whatever the field, and every
        // page that shows err.error.message (130 of them) showed only that.
        // The field reasons (already in the request's language) are the
        // message now; the generic sentence stays the fallback.
        String reasons = fieldErrors.values().stream()
                .filter(message -> message != null && !message.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(" "));
        body.put(FIELD_MESSAGE, reasons.isEmpty()
                ? localized("error.validation.request", "Validation errors in request")
                : reasons);
        body.put(FIELD_ERRORS, fieldErrors);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * QA D896: Spring's own method validation, raised for a constraint on a
     * handler parameter that is not a single bound object, such as each
     * element of a {@code @Valid @RequestBody List<…>} or a constrained
     * {@code @RequestParam}. It is a {@code ResponseStatusException}, so it
     * reached {@link #handleResponseStatus} and the caller as Spring's English
     * "Validation failure" with no field. It now answers like a body
     * refusal: the translated reason of the first violation as
     * {@code message}, and every violation under {@code fieldErrors}, keyed by
     * the body path ({@code [0].criticalHigh}) or the parameter name.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<Object> handleHandlerMethodValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException ex, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (org.springframework.validation.method.ParameterValidationResult result
                : ex.getParameterValidationResults()) {
            String prefix = parameterPath(result);
            if (result instanceof org.springframework.validation.method.ParameterErrors errors) {
                for (FieldError error : errors.getFieldErrors()) {
                    fieldErrors.putIfAbsent(joinPath(prefix, error.getField()), error.getDefaultMessage());
                }
                for (org.springframework.validation.ObjectError error : errors.getGlobalErrors()) {
                    fieldErrors.putIfAbsent(prefix, error.getDefaultMessage());
                }
            } else {
                for (org.springframework.context.MessageSourceResolvable error : result.getResolvableErrors()) {
                    fieldErrors.putIfAbsent(prefix, error.getDefaultMessage());
                }
            }
        }
        String first = fieldErrors.values().stream()
            .filter(message -> message != null && !message.isBlank())
            .findFirst()
            .orElse(null);
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST,
            first != null ? first : localized("error.validation.request", "Validation errors in request"), request);
        body.put(FIELD_ERRORS, fieldErrors);
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /** The body path of a validated parameter: empty for the request body, else its name; with the element index. */
    private static String parameterPath(org.springframework.validation.method.ParameterValidationResult result) {
        org.springframework.core.MethodParameter parameter = result.getMethodParameter();
        String base = parameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class)
            ? ""
            : java.util.Objects.requireNonNullElse(parameter.getParameterName(), "");
        Integer index = result.getContainerIndex();
        if (index != null) {
            return base + "[" + index + "]";
        }
        Object key = result.getContainerKey();
        return key != null ? base + "[" + key + "]" : base;
    }

    private static String joinPath(String prefix, String field) {
        if (prefix.isEmpty()) {
            return field;
        }
        return field == null || field.isEmpty() ? prefix : prefix + "." + field;
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
        body.put(FIELD_MESSAGE, localized("error.validation.parameters", "Constraint violation in request parameters"));
        body.put(FIELD_ERRORS, violations);
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
            localized("error.reference.missing",
                "A referenced record could not be found. This may indicate a data integrity issue. "
                + "Please contact support if this persists."),
            request);
    }

    /**
     * QA D332: a required query parameter left out (a referral completed with
     * no {@code summary}) answered 400 with Spring's empty body, so the portal
     * had nothing to show. The caller is told which value is missing, in its
     * own language.
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex, WebRequest request) {
        String message = localized("error.request.missingParameter",
            "A required value is missing: \"{0}\".", fieldLabel(ex.getParameterName()));
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST, message, request);
        body.put(FIELD_CODE, CODE_MALFORMED_REQUEST);
        body.put(FIELD_FIELD, ex.getParameterName());
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
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
        // The value and the Java type stay in the log (D53): the caller is
        // told which parameter, in its own language, and nothing else.
        if (log.isWarnEnabled()) {
            log.warn("Parameter type mismatch at path {}: parameter {} expected {}",
                request.getDescription(false), ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value");
        }
        String message = localized("error.request.invalidParameter",
            "The value supplied for the parameter \"{0}\" is not valid.", ex.getName());
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST, message, request);
        body.put(FIELD_CODE, CODE_INVALID_PARAMETER);
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * A channel with no transport behind it is a deployment gap, not a caller
     * mistake — 503, and the message reaches the user's screen unprefixed.
     *
     * <p>This handler is what makes the 503 real: {@code @ResponseStatus} on
     * the exception is dead here, because {@link #handleRuntimeException}
     * below claims every RuntimeException and Spring runs
     * {@code ExceptionHandlerExceptionResolver} before
     * {@code ResponseStatusExceptionResolver}. Without this method the
     * receptionist would read "An unexpected error occurred: SMS
     * verification is unavailable right now" off a 500.
     */
    @ExceptionHandler(NotificationTransportUnavailableException.class)
    public ResponseEntity<Object> handleNotificationTransportUnavailable(
            NotificationTransportUnavailableException ex, WebRequest request) {
        // No stack trace: the gateway already logged which property is missing.
        log.warn("Notification transport unavailable at path {}", request.getDescription(false));
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    /**
     * HAPI's {@code BaseServerResponseException} family carries its own HTTP
     * status, but only HAPI's servlet knows that. Thrown out of a service that
     * a plain Spring MVC controller calls — {@code PatientRecordExportController}
     * → {@code PatientEverythingService} is the live case — nothing matched it
     * and it fell through to {@link #handleRuntimeException}, so every intended
     * 403 and 404 on those endpoints surfaced as a 500 with the raw internal
     * message in the body.
     *
     * <p>Note this does NOT shadow the FHIR servlet's own handling: HAPI
     * intercepts these inside its own dispatch and never lets them reach
     * {@code @ControllerAdvice}. This applies only to the plain-MVC callers.
     *
     * <p>The message is echoed only for client errors, which we author
     * ourselves and word deliberately. Anything 5xx gets the generic text —
     * a HAPI parser or transport failure can carry internals we must not
     * return.
     */
    @ExceptionHandler(BaseServerResponseException.class)
    @SuppressWarnings("java:S2629")
    public ResponseEntity<Object> handleFhirResponseException(
            BaseServerResponseException ex, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is4xxClientError()) {
            log.warn("FHIR operation refused at path {} with {}: {}",
                request.getDescription(false), status.value(), ex.getMessage());
            return buildErrorResponse(status, passThrough(ex.getMessage()), request);
        }
        log.error("FHIR operation failed at path {}", request.getDescription(false), ex);
        return buildErrorResponse(status,
            localized("error.unexpected", "An unexpected error occurred. Please try again or contact support."), request);
    }

    @ExceptionHandler(RuntimeException.class)
    @SuppressWarnings("java:S2629")
    public ResponseEntity<Object> handleRuntimeException(RuntimeException ex, WebRequest request) {
    // Log full stack to aid debugging of 500s (safe: message already generic in response)
    log.error("Unhandled runtime exception at path {}: {}", request.getDescription(false), ex.getMessage(), ex);
    // Never echo the exception text: it can carry SQL, class names or row data (D32).
    // The exception text stays in the log (D53): it is an internal Java
    // message ("Cannot invoke ... because ... is null") no user can act on,
    // and it can carry identifiers.
    Map<String, Object> body = errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
        localized("error.unexpected", "An unexpected error occurred. Please try again or contact support."),
        request);
    body.put(FIELD_CODE, CODE_UNEXPECTED_ERROR);
    return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Object> buildErrorResponse(HttpStatus status, String message, WebRequest request) {
        return new ResponseEntity<>(errorBody(status, message, request), status);
    }

    /** The standard error body; a handler that adds a field (E8 #54's {@code code}) builds on it. */
    private Map<String, Object> errorBody(HttpStatus status, String message, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now(ZoneId.systemDefault()));
        body.put(FIELD_STATUS, status.value());
        body.put(FIELD_ERROR, status.getReasonPhrase());
        body.put(FIELD_MESSAGE, message);
        body.put(FIELD_PATH, request.getDescription(false).replace("uri=", ""));
        return body;
    }

    /**
     * E8 #54 — a restricted chart. 403, with a code the portal turns into the
     * break-the-glass declaration prompt; deliberately not the 404 that hides
     * every other refusal, because a restricted chart is meant to be loud.
     */
    @ExceptionHandler(ChartRestrictedException.class)
    public ResponseEntity<Object> handleChartRestricted(ChartRestrictedException ex, WebRequest request) {
        String path = request.getDescription(false);
        log.warn("Restricted chart {} refused at path {}", ex.getPatientId(), path);
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN, ex.getMessage(), request);
        body.put("code", ChartRestrictedException.CODE);
        body.put("patientId", ex.getPatientId());
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * A provider user without a second factor (provider plan AC-13). 403 with
     * the code the portal turns into MFA enrolment or the challenge. The
     * message is already localised; the log carries the path only.
     */
    @ExceptionHandler(MfaEnrollmentRequiredException.class)
    public ResponseEntity<Object> handleMfaEnrollmentRequired(MfaEnrollmentRequiredException ex,
                                                              WebRequest request) {
        // Neither the caller nor the path (it can carry a patient id): the code only.
        log.debug("Provider request without a second factor refused ({})", MfaEnrollmentRequiredException.CODE);
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN, ex.getMessage(), request);
        body.put("code", MfaEnrollmentRequiredException.CODE);
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * A refused MFA step-up. 403 with the reason code, not 401: the session is
     * fine, so the portal shows why instead of signing the user out.
     */
    @ExceptionHandler(MfaStepUpRefusedException.class)
    public ResponseEntity<Object> handleMfaStepUpRefused(MfaStepUpRefusedException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("MFA step-up refused ({}) at path {}", ex.getCode(), request.getDescription(false));
        }
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN, ex.getMessage(), request);
        body.put("code", ex.getCode());
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * A hospital scope the request cannot have (design §3.6). 403 with a code
     * and the reason, so the portal re-reads its scope on a stale chip
     * ({@code NO_LONGER_PERMITTED}) instead of showing the forbidden page.
     */
    @ExceptionHandler(HospitalScopeRefusedException.class)
    public ResponseEntity<Object> handleHospitalScopeRefused(HospitalScopeRefusedException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Hospital scope refused ({}) at path {}", ex.getReason(), request.getDescription(false));
        }
        // QA D258: the message in the caller's language, not the thrower's English.
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN,
            com.example.hms.security.tenant.ActingScopeResolver.localizedRefusalMessage(ex.getReason(), ex.getMessage()),
            request);
        body.put("code", HospitalScopeRefusedException.CODE);
        body.put("reason", ex.getReason());
        if (ex.getRefusedHospitalId() != null) {
            body.put("hospitalId", ex.getRefusedHospitalId().toString());
        }
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * {@link UnauthorizedAccessException} carries {@code @ResponseStatus(FORBIDDEN)},
     * but the {@code RuntimeException} catch-all below wins over the annotation,
     * so every one of them answered a 500 "unexpected error" (QA D226). A keyed
     * refusal is resolved in the request locale; a free sentence stays in the
     * log only, since it can carry a username or an id.
     */
    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<Object> handleUnauthorizedAccess(UnauthorizedAccessException ex, WebRequest req) {
        String raw = ex.getMessage();
        if (com.example.hms.utility.MessageUtil.isMessageKey(raw)) {
            log.warn("Access refused: {}", raw);
            return buildErrorResponse(HttpStatus.FORBIDDEN,
                com.example.hms.utility.MessageUtil.resolveOrRaw(raw, ex.getArgs()), req);
        }
        if (log.isWarnEnabled()) {
            log.warn("Access refused at path {}", req.getDescription(false));
        }
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN,
            localized("error.access.denied", "Access denied"), req);
        body.put(FIELD_CODE, CODE_ACCESS_DENIED);
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    /**
     * 403. The message is the literal "Access denied" unless the thrower opted
     * in with {@link ClientSafeAccessDeniedException}, whose contract is that
     * its message is written for the caller and discloses nothing across
     * tenants. Every other message — a {@code @PreAuthorize} refusal, or a
     * sentence composed deep in a service — stays in the log.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest req) {
        log.warn("Access denied: {}", ex.getMessage());
        if (ex instanceof ClientSafeAccessDeniedException && ex.getMessage() != null) {
            // QA D1187 (round 22): a thrower may hand a bundle KEY; it is resolved
            // in the request locale here, and a resolved sentence passes unchanged.
            return buildErrorResponse(HttpStatus.FORBIDDEN, passThrough(ex.getMessage()), req);
        }
        // The opaque default is localized now (D174), so clients that hid it by
        // comparing the English literal key on this code instead.
        Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN,
            localized("error.access.denied", "Access denied"), req);
        body.put(FIELD_CODE, CODE_ACCESS_DENIED);
        return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleMaxUploadSize(MaxUploadSizeExceededException ex, WebRequest request) {
        log.warn("File upload too large: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE,
            localized("error.upload.tooLarge", "File size exceeds the maximum allowed limit of {0}",
                com.example.hms.service.FileUploadService.MAX_UPLOAD_LABEL), request);
    }


}
