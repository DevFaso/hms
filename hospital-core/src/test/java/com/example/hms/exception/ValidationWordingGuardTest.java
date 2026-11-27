package com.example.hms.exception;

import com.example.hms.i18n.TestMessageSources;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Locale;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationWordingGuardTest {
    static class Input {
        @NotBlank public String reason = "";
        @NotNull public String hospitalId;
        @Min(1) public int quantity = 0;
        @NotBlank(message = "custom instruction") public String name = "";
    }
    static void endpoint(Input body) { }

    @Test
    void methodParameterConstraintUsesTheSameMinimumWording() throws Exception {
        var handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violation = factory.getValidator().validate(new Input()).stream()
                .filter(v -> v.getPropertyPath().toString().equals("quantity")).findFirst().orElseThrow();
            var parameter = new MethodParameter(ValidationWordingGuardTest.class.getDeclaredMethod("endpoint", Input.class), 0);
            var error = new org.springframework.context.support.DefaultMessageSourceResolvable(new String[] {"min"}, null,
                violation.getMessage());
            var result = new org.springframework.validation.method.ParameterValidationResult(parameter, null,
                java.util.List.of(error), null, null, null, (ignored, type) -> violation);
            var exception = org.mockito.Mockito.mock(org.springframework.web.method.annotation.HandlerMethodValidationException.class);
            org.mockito.Mockito.when(exception.getParameterValidationResults()).thenReturn(java.util.List.of(result));
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            var response = (Map<?, ?>) handler.handleHandlerMethodValidation(exception,
                new ServletWebRequest(new MockHttpServletRequest())).getBody();
            assertThat((String) response.get("message")).contains("at least", "1");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void defaultConstraintsUseHouseWordingOnBothValidationPaths() throws Exception {
        var handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (Locale locale : new Locale[] { Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("es") }) {
                LocaleContextHolder.setLocale(locale);
                var input = new Input();
                var request = new ServletWebRequest(new MockHttpServletRequest());
                var violations = validator.validate(input);
                var body = (Map<?, ?>) handler.handleConstraintViolation(new ConstraintViolationException(violations), request).getBody();
                var fields = (Map<?, ?>) body.get("fieldErrors");
                assertThat(fields.keySet().stream().map(Object::toString).toList())
                    .contains("reason", "hospitalId", "quantity", "name");
                String required = locale.equals(Locale.FRENCH) ? "Veuillez renseigner" : "Please enter";
                String minimum = locale.equals(Locale.FRENCH) ? "au moins" : "at least";
                assertThat((String) fields.get("reason")).contains(required).doesNotContain("ne doit pas");
                assertThat((String) fields.get("hospitalId")).contains(required);
                assertThat((String) fields.get("quantity")).contains(minimum, "1");
                assertThat(fields.get("name")).isEqualTo("custom instruction");
                var binding = new BeanPropertyBindingResult(input, "body");
                new SpringValidatorAdapter(validator).validate(input, binding);
                var parameter = new MethodParameter(ValidationWordingGuardTest.class.getDeclaredMethod("endpoint", Input.class), 0);
                var mvc = (Map<?, ?>) handler.handleValidationException(new MethodArgumentNotValidException(parameter, binding), request).getBody();
                var mvcFields = (Map<?, ?>) mvc.get("fieldErrors");
                assertThat((String) mvcFields.get("reason")).contains(required);
                assertThat((String) mvcFields.get("quantity")).contains(minimum, "1");
                assertThat(mvcFields.get("name")).isEqualTo("custom instruction");
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
