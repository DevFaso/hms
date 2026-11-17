package com.example.hms.payload.dto;

import com.example.hms.exception.GlobalExceptionHandler;
import com.example.hms.i18n.TestMessageSources;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1387 (round 32): a 51-character unit or 2,100 characters of notes on
 * POST /api/lab-results reached the database and came back as a generic
 * "violates a data constraint" 400 naming no field. Each is now refused at the
 * edge on its own field, named by its label, in every language.
 */
@DisplayName("D1387: an over-long lab result unit or notes is refused on its field")
class LabResultRequestDTOValidationTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("resultUnit over 50 and notes over 2048 are each named in fieldErrors, with their label")
    void overLongUnitAndNotesAreNamed() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/lab-results");
        LabResultRequestDTO body = LabResultRequestDTO.builder()
            .labOrderId(UUID.randomUUID()).assignmentId(UUID.randomUUID()).patientId(UUID.randomUUID())
            .resultValue("4.2").resultDate(java.time.LocalDateTime.of(2026, 11, 3, 8, 0)).resultUnit("u".repeat(51)).notes("n".repeat(2100)).build();
        for (String lang : new String[] {"en", "fr", "es"}) {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(lang));
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(body, "labResultRequestDTO");
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                new SpringValidatorAdapter(factory.getValidator()).validate(body, result);
            }
            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new MethodParameter(Object.class.getMethod("toString"), -1), result);
            Map<?, ?> response = (Map<?, ?>) handler.handleValidationException(ex, request).getBody();
            Map<?, ?> fieldErrors = (Map<?, ?>) response.get("fieldErrors");
            assertThat(fieldErrors.keySet().stream().map(String::valueOf).toList()).as(lang)
                .containsExactlyInAnyOrder("resultUnit", "notes");
            String unitLabel = TestMessageSources.bundles().getMessage("field.label.resultUnit", null,
                Locale.forLanguageTag(lang));
            assertThat((String) fieldErrors.get("resultUnit")).as(lang).startsWith(unitLabel);
        }
    }

    @Test
    @DisplayName("a unit of exactly 50 and notes of exactly 2048 characters pass")
    void valuesAtTheColumnLengthPass() {
        LabResultRequestDTO body = LabResultRequestDTO.builder()
            .labOrderId(UUID.randomUUID()).assignmentId(UUID.randomUUID()).patientId(UUID.randomUUID())
            .resultValue("4.2").resultDate(java.time.LocalDateTime.of(2026, 11, 3, 8, 0)).resultUnit("u".repeat(50)).notes("n".repeat(2048)).build();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(body)).isEmpty();
        }
    }
}
