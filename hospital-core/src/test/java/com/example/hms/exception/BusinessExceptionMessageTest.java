package com.example.hms.exception;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BusinessException} resolves a message key the way
 * {@link ResourceNotFoundException} does — in the caller's locale, with
 * arguments — and leaves free text exactly as the site wrote it. Before this,
 * a key reached the API client verbatim: {@code prescription.patient.required}
 * was the whole error body.
 */
@DisplayName("BusinessException message keys")
class BusinessExceptionMessageTest {

    @BeforeEach
    void realBundles() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("a key resolves in the request's locale")
    void keyResolvesPerLocale() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(new BusinessException("prescription.patient.required"))
            .hasMessage("A patient is required to create a prescription.");

        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(new BusinessException("prescription.patient.required"))
            .hasMessage("Un patient est obligatoire pour créer une ordonnance.");

        LocaleContextHolder.setLocale(Locale.of("es"));
        assertThat(new BusinessException("prescription.patient.required"))
            .hasMessage("Se requiere un paciente para crear una receta.");
    }

    @Test
    @DisplayName("arguments land in the resolved message")
    void argumentsAreRendered() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        UUID assignmentHospital = UUID.randomUUID();
        UUID invoiceHospital = UUID.randomUUID();

        BusinessException ex = new BusinessException(
            "assignment.hospital.mismatch", assignmentHospital, invoiceHospital);

        assertThat(ex).hasMessage("Assignment hospital (" + assignmentHospital
            + ") does not match invoice hospital (" + invoiceHospital + ")");
        assertThat(ex.getMessageKey()).isEqualTo("assignment.hospital.mismatch");
        assertThat(ex.getArgs()).containsExactly(assignmentHospital, invoiceHospital);
    }

    @Test
    @DisplayName("free text is never looked up, so its apostrophes and braces survive")
    void freeTextPassesThrough() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        // A MessageFormat pass would eat the lone apostrophe and the braces.
        String prose = "The patient's chart {0} is locked.";

        assertThat(new BusinessException(prose)).hasMessage(prose);
        assertThat(new BusinessException(prose, new IllegalStateException("cause"))).hasMessage(prose);
    }

    @Test
    @DisplayName("a key no bundle carries falls back to the raw text, with no marker")
    void unknownKeyFallsBackToRawText() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        assertThat(new BusinessException("no.such.key.anywhere")).hasMessage("no.such.key.anywhere");
    }

    @Test
    @DisplayName("the cause constructor resolves too")
    void causeConstructorResolves() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        IllegalStateException cause = new IllegalStateException("json");

        BusinessException ex = new BusinessException("permission.matrix.serialization.error", cause);

        assertThat(ex).hasMessage("The permission matrix could not be read or saved.").hasCause(cause);
    }

    @Test
    @DisplayName("the handler keeps the 400 and the error-body shape, carrying the resolved text")
    void handlerShapeIsUnchanged() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/prescriptions");

        ResponseEntity<Object> response = new GlobalExceptionHandler()
            .handleBusinessException(new BusinessException("prescription.patient.required"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path");
        assertThat(body)
            .containsEntry("status", 400)
            .containsEntry("error", "Bad Request")
            .containsEntry("message", "Un patient est obligatoire pour créer une ordonnance.")
            .containsEntry("path", "/api/prescriptions");
    }
}
