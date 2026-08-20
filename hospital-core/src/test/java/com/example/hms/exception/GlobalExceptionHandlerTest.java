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
}
