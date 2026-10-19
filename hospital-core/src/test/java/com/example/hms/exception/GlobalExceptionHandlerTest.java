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
    // handleAccessDenied
    // =========================================================================

    @Nested
    @DisplayName("handleAccessDenied")
    class HandleAccessDenied {

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(ResponseEntity<Object> response) {
            return (Map<String, Object>) response.getBody();
        }

        @Test
        @DisplayName("a plain AccessDeniedException keeps the opaque default — its message could disclose a tenant")
        void plainAccessDeniedIsOpaque() {
            var ex = new org.springframework.security.access.AccessDeniedException(
                "Prescription belongs to hospital B");

            ResponseEntity<Object> response = handler.handleAccessDenied(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response)).containsEntry("message", "Access denied");
        }

        @Test
        @DisplayName("a ClientSafeAccessDeniedException reaches the caller with its sentence")
        void clientSafeMessageIsShown() {
            var ex = new ClientSafeAccessDeniedException(
                "Only the prescribing clinician can sign this prescription.");

            ResponseEntity<Object> response = handler.handleAccessDenied(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bodyOf(response))
                .containsEntry("message", "Only the prescribing clinician can sign this prescription.");
        }
    }

    // =========================================================================
    // handleNotificationTransportUnavailable
    // =========================================================================

    @Test
    @DisplayName("an unhandled runtime exception never echoes its text to the client (D32)")
    void runtimeExceptionTextIsNotReturned() {
        ResponseEntity<Object> response = handler.handleRuntimeException(
            new NullPointerException("Cannot invoke java.util.Collection.size() because <parameter1> is null"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // D53 replaced the bare D32 text with the translated error.unexpected message.
        assertThat(String.valueOf(body.get("message")))
            .isEqualTo("An unexpected error occurred. Please try again or contact support.")
            .doesNotContain("Cannot invoke", "parameter1");
    }

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

        private org.springframework.http.converter.HttpMessageNotReadableException wrap(Throwable cause) {
            return new org.springframework.http.converter.HttpMessageNotReadableException(
                "Outer wrapper", cause,
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(ResponseEntity<Object> response) {
            return (Map<String, Object>) response.getBody();
        }

        /**
         * D53: the dev QA run saw "Cannot deserialize value of type
         * `com.example.hms.enums.PharmacyType` ..." in a toast. The parser's
         * text, Java types and enum constants must never reach the caller.
         */
        @Test
        @DisplayName("returns 400 with a safe message, never the Jackson text")
        void neverEchoesJacksonText() {
            String jacksonMessage = "Cannot deserialize value of type "
                + "`com.example.hms.enums.PharmacyType` from String \"COMMUNITY\": "
                + "not one of the values accepted for Enum class: "
                + "[COMMUNITY_PHARMACY, PARTNER_PHARMACY, HOSPITAL_DISPENSARY]";
            var ex = wrap(tools.jackson.databind.exc.MismatchedInputException.from(
                (tools.jackson.core.JsonParser) null, Object.class, jacksonMessage));

            ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(ex, request);

            Map<String, Object> body = bodyOf(response);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body).containsEntry("status", 400).containsEntry("code", "MALFORMED_REQUEST");
            assertThat((String) body.get("message"))
                .isEqualTo("The request could not be read. Check the values entered and try again.")
                .doesNotContain("deserialize").doesNotContain("com.example").doesNotContain("COMMUNITY");
        }

        @Test
        @DisplayName("names the offending JSON field when Jackson reports a path")
        void namesTheField() {
            var cause = tools.jackson.databind.exc.MismatchedInputException.from(
                (tools.jackson.core.JsonParser) null, Object.class, "Cannot deserialize `java.time.LocalDate`");
            cause.prependPath(new tools.jackson.core.JacksonException.Reference(null, 0));
            cause.prependPath(new tools.jackson.core.JacksonException.Reference(null, "items"));
            var ex = wrap(cause);

            Map<String, Object> body = bodyOf(handler.handleHttpMessageNotReadable(ex, request));

            assertThat(body).containsEntry("field", "items[0]");
            assertThat((String) body.get("message"))
                .isEqualTo("The value entered for \"items[0]\" is not valid.")
                .doesNotContain("LocalDate");
        }

        @Test
        @DisplayName("a missing body still gets the safe message, never 'null'")
        void missingBody() {
            var ex = new org.springframework.http.converter.HttpMessageNotReadableException(
                "Required request body is missing",
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));

            Map<String, Object> body = bodyOf(handler.handleHttpMessageNotReadable(ex, request));

            assertThat((String) body.get("message"))
                .isEqualTo("The request could not be read. Check the values entered and try again.")
                .doesNotContain("null").doesNotContain("Required request body");
        }

        @Test
        @DisplayName("uses the request locale through the MessageSource")
        void translatesThroughMessageSource() {
            var source = new org.springframework.context.support.ReloadableResourceBundleMessageSource();
            source.setBasenames(com.example.hms.config.MessageBundles.basenames());
            source.setDefaultEncoding("UTF-8");
            source.setFallbackToSystemLocale(false);
            source.setAlwaysUseMessageFormat(true);
            handler.setMessageSource(source);
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            try {
                var ex = new org.springframework.http.converter.HttpMessageNotReadableException(
                    "x", new org.springframework.mock.http.MockHttpInputMessage(new byte[0]));
                Map<String, Object> body = bodyOf(handler.handleHttpMessageNotReadable(ex, request));
                assertThat((String) body.get("message"))
                    .isEqualTo("La demande n'a pas pu être lue. Vérifiez les valeurs saisies et réessayez.");
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        }
    }

    @Nested
    @DisplayName("handleRuntimeException")
    class HandleRuntimeException {

        @Test
        @DisplayName("D53: the catch-all 500 never echoes the exception text")
        void neverEchoesExceptionText() {
            var ex = new NullPointerException("Cannot invoke \"User.getId()\" because \"user\" is null");

            ResponseEntity<Object> response = handler.handleRuntimeException(ex, request);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(body).containsEntry("code", "UNEXPECTED_ERROR");
            assertThat((String) body.get("message"))
                .isEqualTo("An unexpected error occurred. Please try again or contact support.")
                .doesNotContain("Cannot invoke");
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
            // D53: the parameter is named, the raw value and Java type are not.
            org.assertj.core.api.Assertions.assertThat(String.valueOf(response.getBody()))
                .contains("sources").contains("INVALID_PARAMETER")
                .doesNotContain("PLATFORM_CONFIG").doesNotContain("AuditSource");
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
            assertThat(body).isNotNull().containsEntry("message", "An unexpected error occurred. Please try again or contact support.");
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

    // =========================================================================
    // handleBusinessRuleException (D10)
    // =========================================================================

    @Nested
    @DisplayName("handleBusinessRuleException")
    class HandleBusinessRuleException {

        @org.springframework.web.bind.annotation.RestController
        static class Thrower {
            @org.springframework.web.bind.annotation.GetMapping("/rule")
            String rule() {
                throw new BusinessRuleException("Purge cannot be scheduled in the past.");
            }
        }

        @Test
        @DisplayName("a rule refusal is a 400 with its sentence, through MVC dispatch — it used to fall to the 500 handler")
        void dispatchesTo400() throws Exception {
            org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new Thrower())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/rule"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                    .value("Purge cannot be scheduled in the past."));
        }

        @Test
        @DisplayName("a keyed refusal is resolved in the caller's locale")
        void keyIsResolved() {
            com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            try {
                ResponseEntity<Object> response = handler.handleBusinessRuleException(
                    BusinessRuleException.ofKey("platform.department.noHospital"), request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body).containsEntry("message", "Le service hospitalier n'est rattaché à aucun hôpital.");
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        }
    }

    @Nested
    @DisplayName("handleConcurrentModification (G15 rule 1)")
    class HandleConcurrentModification {

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodyOf(ResponseEntity<Object> response) {
            return (Map<String, Object>) response.getBody();
        }

        private void inFrench(Runnable body) {
            com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
            try {
                body.run();
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        }

        @Test
        @DisplayName("a lock that could not be taken (or a broken deadlock) is a 409, not a 500")
        void pessimisticLockIsConflict() {
            inFrench(() -> {
                ResponseEntity<Object> response = handler.handleConcurrentModification(
                    new org.springframework.dao.CannotAcquireLockException("deadlock detected"), request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(bodyOf(response)).containsEntry("message",
                    "Cet enregistrement a été modifié par quelqu'un d'autre au même moment. Rechargez et réessayez.");
            });
        }

        @Test
        @DisplayName("a failed @Version check is a 409 with the same message")
        void optimisticLockIsConflict() {
            inFrench(() -> {
                ResponseEntity<Object> response = handler.handleConcurrentModification(
                    new org.springframework.orm.ObjectOptimisticLockingFailureException("Prescription", "id"), request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(bodyOf(response).get("message").toString()).startsWith("Cet enregistrement");
            });
        }

        @Test
        @DisplayName("both families are mapped on the handler, so neither falls to the RuntimeException catch-all")
        void handlerDeclaresBothFamilies() throws Exception {
            org.springframework.web.bind.annotation.ExceptionHandler mapping = GlobalExceptionHandler.class
                .getMethod("handleConcurrentModification", RuntimeException.class, WebRequest.class)
                .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);

            assertThat(mapping.value()).containsExactlyInAnyOrder(
                org.springframework.dao.PessimisticLockingFailureException.class,
                org.springframework.dao.OptimisticLockingFailureException.class);
        }
    }

    // =========================================================================
    // D174: every handler-authored or key-carrying message follows Accept-Language
    // =========================================================================

    @Nested
    @DisplayName("D174 French request locale")
    class FrenchLocale {

        private org.springframework.context.support.ReloadableResourceBundleMessageSource source;

        @BeforeEach
        void french() {
            source = new org.springframework.context.support.ReloadableResourceBundleMessageSource();
            source.setBasenames(com.example.hms.config.MessageBundles.basenames());
            source.setDefaultEncoding("UTF-8");
            source.setFallbackToSystemLocale(false);
            source.setAlwaysUseMessageFormat(true);
            handler.setMessageSource(source);
            com.example.hms.utility.MessageUtil.setMessageSource(source);
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        }

        @org.junit.jupiter.api.AfterEach
        void reset() {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }

        @SuppressWarnings("unchecked")
        private String messageOf(ResponseEntity<Object> response) {
            return (String) ((Map<String, Object>) response.getBody()).get("message");
        }

        @Test
        @DisplayName("the D131 not-null violation is answered in French")
        void notNullViolationInFrench() {
            var ex = new org.springframework.dao.DataIntegrityViolationException("x",
                new RuntimeException("null value in column \"created_at\" violates not-null constraint"));
            assertThat(messageOf(handler.handleDataIntegrityViolation(ex, request)))
                .isEqualTo("Une valeur obligatoire est manquante. Contactez le support si le problème persiste.");
        }

        @Test
        @DisplayName("a duplicate key is answered in French")
        void duplicateInFrench() {
            var ex = new org.springframework.dao.DataIntegrityViolationException("x",
                new RuntimeException("duplicate key value violates unique constraint"));
            assertThat(messageOf(handler.handleDataIntegrityViolation(ex, request)))
                .isEqualTo("Cet enregistrement existe déjà.");
        }

        @Test
        @DisplayName("access denied is answered in French")
        void accessDeniedInFrench() {
            ResponseEntity<Object> response = handler.handleAccessDenied(
                new org.springframework.security.access.AccessDeniedException("internal"), request);
            assertThat(messageOf(response)).isEqualTo("Accès refusé");
            assertThat(response.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("code", "ACCESS_DENIED");
        }

        @Test
        @DisplayName("an IllegalArgumentException thrown with a bundle key is resolved, not echoed")
        void illegalArgumentKeyResolved() {
            assertThat(messageOf(handler.handleIllegalArgumentException(
                new IllegalArgumentException("error.session.expired"), request)))
                .isEqualTo("Session expirée. Veuillez vous reconnecter.");
        }

        @Test
        @DisplayName("an IllegalStateException with free text passes through unchanged")
        void freeTextUnchanged() {
            assertThat(messageOf(handler.handleIllegalStateException(
                new IllegalStateException("Texte libre déjà rédigé."), request)))
                .isEqualTo("Texte libre déjà rédigé.");
        }

        @Test
        @DisplayName("a ResponseStatusException keeps its 400 and is no longer a 500")
        void responseStatusKeepsStatus() {
            var response = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "error.access.denied"),
                request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(messageOf(response)).isEqualTo("Accès refusé");
        }
    }
}
