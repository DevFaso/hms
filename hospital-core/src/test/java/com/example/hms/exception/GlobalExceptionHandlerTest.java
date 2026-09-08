package com.example.hms.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private WebRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/users");
    }

    // =========================================================================
    // handleNotificationTransportUnavailable
    // =========================================================================

    @Nested
    @DisplayName("handleNotificationTransportUnavailable")
    class HandleNotificationTransportUnavailable {

        @Test
        @DisplayName("returns 503 and the message unprefixed — @ResponseStatus alone would be dead here")
        void returns503WithTheMessageIntact() {
            // handleRuntimeException claims every RuntimeException, and Spring
            // runs ExceptionHandlerExceptionResolver before the
            // ResponseStatusExceptionResolver that would read @ResponseStatus.
            // Without the dedicated handler this is a 500 whose body reads
            // "An unexpected error occurred: ...".
            var ex = new NotificationTransportUnavailableException(
                "SMS verification is unavailable right now.");

            ResponseEntity<Object> response =
                handler.handleNotificationTransportUnavailable(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("message", "SMS verification is unavailable right now.");
            assertThat(String.valueOf(body.get("message"))).doesNotContain("unexpected error");
        }
    }

    // =========================================================================
    // handleConflictException — core new handler
    // =========================================================================

    @Nested
    @DisplayName("handleConflictException")
    class HandleConflictException {

        @Test
        @DisplayName("returns 409 HTTP status for any ConflictException")
        void returns409Status() {
            ConflictException ex = new ConflictException("email:Email is already registered.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("extracts email field from 'email:...' prefixed message")
        void extractsEmailField() {
            ConflictException ex = new ConflictException("email:Email 'a@b.com' is already registered.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("field", "email")
                .containsEntry("message", "Email 'a@b.com' is already registered.");
        }

        @Test
        @DisplayName("extracts username field from 'username:...' prefixed message")
        void extractsUsernameField() {
            ConflictException ex = new ConflictException("username:Username 'johndoe' is already taken.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("field", "username")
                .containsEntry("message", "Username 'johndoe' is already taken.");
        }

        @Test
        @DisplayName("extracts phone field from 'phone:...' prefixed message")
        void extractsPhoneField() {
            ConflictException ex = new ConflictException("phone:Phone number '+1234' is already registered.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("field", "phone")
                .containsEntry("message", "Phone number '+1234' is already registered.");
        }

        @Test
        @DisplayName("plain message (no colon) → no 'field' key in response body")
        void noFieldKeyForPlainMessage() {
            ConflictException ex = new ConflictException("Duplicate resource detected.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .doesNotContainKey("field")
                .containsEntry("message", "Duplicate resource detected.");
        }

        @Test
        @DisplayName("body always contains status=409, error=Conflict, path and timestamp keys")
        void bodyContainsRequiredKeys() {
            ConflictException ex = new ConflictException("email:Already used.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsKey("timestamp")
                .containsEntry("status", 409)
                .containsEntry("error", "Conflict")
                .containsEntry("path", "/api/v1/users");
        }

        @Test
        @DisplayName("null message → no 'field' key and null 'message' value, still 409")
        void handlesNullMessage() {
            ConflictException ex = new ConflictException(null);
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull().doesNotContainKey("field");
            assertThat(body.get("message")).isNull();
        }

        @Test
        @DisplayName("message with multiple colons splits only on the first colon")
        void splitsOnFirstColonOnly() {
            ConflictException ex = new ConflictException("email:Value 'a:b' is already used.");
            ResponseEntity<Object> response = handler.handleConflictException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("field", "email")
                .containsEntry("message", "Value 'a:b' is already used.");
        }
    }

    // =========================================================================
    // handleIllegalArgumentException
    // =========================================================================

    @Nested
    @DisplayName("handleIllegalArgumentException")
    class HandleIllegalArgumentException {

        @Test
        @DisplayName("returns 400 with the exception message")
        void returns400() {
            IllegalArgumentException ex = new IllegalArgumentException("Invalid parameter");
            ResponseEntity<Object> response = handler.handleIllegalArgumentException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).isNotNull()
                .containsEntry("message", "Invalid parameter")
                .containsEntry("status", 400);
        }
    }

    // =========================================================================
    // handleIllegalStateException
    // =========================================================================

    // =========================================================================
    // handleHttpMessageNotReadable — Jackson deserialisation failures
    // =========================================================================

    @Nested
    @DisplayName("handleHttpMessageNotReadable")
    class HandleHttpMessageNotReadable {

        /**
         * Regression for the dev 500 reported on 2026-05-10 where the
         * pharmacy-registry frontend posted {@code "pharmacyType":"COMMUNITY"}
         * against a Java enum that expected {@code COMMUNITY_PHARMACY}.
         * Jackson raised {@code HttpMessageNotReadableException}, the
         * handler used to fall through to the catch-all RuntimeException
         * branch and return a generic 500. Now it returns a 400 with the
         * underlying Jackson message ("not one of the values accepted")
         * so the frontend can self-diagnose enum drift.
         */
        @Test
        @DisplayName("returns 400 with the original Jackson message preserved")
        void returns400WithJacksonMessage() {
            String jacksonMessage = "Cannot deserialize value of type "
                + "`com.example.hms.enums.PharmacyType` from String \"COMMUNITY\": "
                + "not one of the values accepted for Enum class: "
                + "[COMMUNITY_PHARMACY, PARTNER_PHARMACY, HOSPITAL_DISPENSARY]";
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                    "Outer wrapper",
                    new com.fasterxml.jackson.databind.JsonMappingException(null, jacksonMessage),
                    new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

            ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).isNotNull().containsEntry("status", 400);
            assertThat((String) body.get("message"))
                .startsWith("Malformed request body:")
                .contains("not one of the values accepted")
                .contains("COMMUNITY_PHARMACY");
        }

        @Test
        @DisplayName("falls back to outer message when no cause is set")
        void fallsBackWhenCauseAbsent() {
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                    "Required request body is missing",
                    new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

            ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).isNotNull();
            assertThat((String) body.get("message"))
                .startsWith("Malformed request body:")
                .contains("Required request body is missing");
        }

        /**
         * Regression guard against the Copilot-flagged "Malformed request
         * body: null" output: when both the cause's message and the outer
         * exception's message are blank, the handler must fall through to a
         * fixed default rather than serialise {@code null} into the response.
         */
        @Test
        @DisplayName("falls back to a fixed default when both messages are blank")
        void fallsBackToFixedDefaultWhenAllMessagesBlank() {
            // Cause exists but has a null message — historically the source
            // of the "Malformed request body: null" footgun.
            com.fasterxml.jackson.databind.JsonMappingException blankCause =
                new com.fasterxml.jackson.databind.JsonMappingException(null, (String) null);
            org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                    "",
                    blankCause,
                    new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

            ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).isNotNull();
            // The exact wording is allowed to vary — Spring's
            // HttpMessageNotReadableException normalises an empty message to
            // "N/A" before we see it, so we land on
            // "Malformed request body: N/A". The load-bearing invariant is
            // simply that the response never serialises the literal string
            // "null" into the body, which was Copilot's concern.
            assertThat((String) body.get("message"))
                .startsWith("Malformed request body")
                .doesNotContain("null");
        }
    }

    // =========================================================================
    // handleIllegalStateException
    // =========================================================================

    @Nested
    @DisplayName("handleIllegalStateException")
    class HandleIllegalStateException {

        @Test
        @DisplayName("returns 400 with the exception message")
        void returns400() {
            IllegalStateException ex = new IllegalStateException("Business rule violated");
            ResponseEntity<Object> response = handler.handleIllegalStateException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).isNotNull()
                .containsEntry("message", "Business rule violated")
                .containsEntry("status", 400);
        }
    }

    @Nested
    @DisplayName("handleTypeMismatch")
    class HandleTypeMismatch {

        @Test
        @DisplayName("parameter-binding failures return 400, not the 500 catch-all")
        void returns400ForUnconvertibleParam() throws NoSuchMethodException {
            var param = new org.springframework.core.MethodParameter(
                Object.class.getMethod("equals", Object.class), 0);
            var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "PLATFORM_CONFIG", com.example.hms.enums.AuditSource.class, "sources", param,
                new IllegalArgumentException("No enum constant"));

            var response = handler.handleTypeMismatch(ex, request);

            org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(400);
            org.assertj.core.api.Assertions.assertThat(String.valueOf(response.getBody()))
                .contains("sources").contains("PLATFORM_CONFIG");
        }
    }

    @Nested
    @DisplayName("handlePatientAlreadyRegistered")
    class HandlePatientAlreadyRegistered {

        @Test
        @DisplayName("duplicate hospital registration returns 409, not the 500 catch-all")
        void returns409ForDuplicateRegistration() {
            var ex = new PatientAlreadyRegisteredException(
                "Patient 'p1' is already registered to Hospital 'h1'.");

            ResponseEntity<Object> response = handler.handlePatientAlreadyRegistered(ex, request);

            org.assertj.core.api.Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            org.assertj.core.api.Assertions.assertThat(String.valueOf(response.getBody()))
                .contains("already registered");
        }
    }

    // =========================================================================
    // handleUnauthorized — a rejected MFA step-up is a 401, not a 500
    // =========================================================================
    @Nested
    @DisplayName("handleUnauthorized")
    class HandleUnauthorized {

        @Test
        @DisplayName("returns 401 with the exception message, not the RuntimeException 500")
        void returns401WithMessage() {
            UnauthorizedException ex = new UnauthorizedException(
                "mfa_required: invalid or missing X-Mfa-Token for emergency broadcast");
            ResponseEntity<Object> response = handler.handleUnauthorized(ex, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("status", 401)
                .containsEntry("message", "mfa_required: invalid or missing X-Mfa-Token for emergency broadcast");
        }

        @Test
        @DisplayName("is declared for UnauthorizedException so Spring routes it here before the RuntimeException catch-all")
        void isRegisteredForUnauthorizedException() throws NoSuchMethodException {
            var handlerMethod = GlobalExceptionHandler.class.getMethod(
                "handleUnauthorized", UnauthorizedException.class, WebRequest.class);
            var annotation = handlerMethod.getAnnotation(
                org.springframework.web.bind.annotation.ExceptionHandler.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).containsExactly(UnauthorizedException.class);
        }
    }

    // =========================================================================
    // handleFhirResponseException
    // =========================================================================

    @Nested
    @DisplayName("handleFhirResponseException")
    class HandleFhirResponseException {

        @Test
        @DisplayName("maps HAPI's ResourceNotFoundException to 404, not the RuntimeException catch-all's 500")
        void mapsNotFoundTo404() {
            // The live bug: PatientRecordExportController is plain Spring MVC,
            // so HAPI's servlet never sees this exception and its status code
            // was thrown away. GET /patients/{id}/fhir-record answered 500 for
            // every patient outside the caller's hospital scope.
            var ex = new ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException(
                "Patient/abc is not registered at your active hospital.");

            ResponseEntity<Object> response = handler.handleFhirResponseException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull()
                .containsEntry("status", 404)
                .containsEntry("message", "Patient/abc is not registered at your active hospital.");
        }

        @Test
        @DisplayName("maps HAPI's ForbiddenOperationException to 403")
        void mapsForbiddenTo403() {
            var ex = new ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException(
                "An active hospital scope is required.");

            ResponseEntity<Object> response = handler.handleFhirResponseException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("maps HAPI's MethodNotAllowedException to 405")
        void mapsMethodNotAllowedTo405() {
            var ex = new ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException(
                "FHIR Patient/{id}/$everything is disabled.");

            ResponseEntity<Object> response = handler.handleFhirResponseException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        @DisplayName("withholds the message on a 5xx, where it can carry HAPI internals")
        void hidesTheMessageOnServerErrors() {
            // Client errors are ours and worded deliberately, so they are
            // echoed. A 5xx can come from HAPI's parser or transport and must
            // not be handed back to the caller.
            var ex = new ca.uhn.fhir.rest.server.exceptions.InternalErrorException(
                "jdbc:postgresql://internal-host/hms failed");

            ResponseEntity<Object> response = handler.handleFhirResponseException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).isNotNull().containsEntry("message", "An unexpected error occurred.");
            assertThat(body.get("message").toString()).doesNotContain("internal-host");
        }

        @Test
        @DisplayName("falls back to 500 when HAPI reports a status Spring cannot resolve")
        void fallsBackTo500OnAnUnknownStatus() {
            var ex = new ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException(799, "odd") {};

            ResponseEntity<Object> response = handler.handleFhirResponseException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("is declared for the HAPI base type, so every subclass routes here before the RuntimeException catch-all")
        void isRegisteredForTheHapiBaseType() throws NoSuchMethodException {
            // The whole family has to be covered by one declaration: a handler
            // registered for the leaf types alone would leave any HAPI
            // exception we do not yet throw falling through to the 500.
            var handlerMethod = GlobalExceptionHandler.class.getMethod(
                "handleFhirResponseException",
                ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException.class,
                WebRequest.class);
            var annotation = handlerMethod.getAnnotation(
                org.springframework.web.bind.annotation.ExceptionHandler.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).containsExactly(
                ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException.class);
        }
    }
}
