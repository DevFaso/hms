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
}
