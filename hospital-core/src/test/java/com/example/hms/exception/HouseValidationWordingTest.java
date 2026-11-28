package com.example.hms.exception;

import com.example.hms.i18n.TestMessageSources;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3861 (r77, r81) / D4026 / D3852 class: a request constraint left on bean
 * validation's default message reached the reader as the validator's own
 * sentence - « motif : ne doit pas être vide », « motif : la taille doit être
 * comprise entre 10 et 1024 ». Every constraint kind {@link GlobalExceptionHandler}
 * words is refused here, in each language, and the reason must be the house
 * wording: the field's label, no validator text, no unresolved key.
 */
@DisplayName("D3861: a default-message constraint is refused in the house wording")
class HouseValidationWordingTest {

    /** One field per house-worded constraint kind, each holding a value the constraint refuses. */
    @SuppressWarnings("unused")
    static class Everything {
        @NotNull String status;
        @NotBlank String reason = "  ";
        @NotEmpty List<String> tags = List.of();
        @Size(min = 10, max = 1024) String notes = "short";
        @Size(max = 2) List<String> items = List.of("a", "b", "c");
        @Min(15) Integer minutes = 1;
        @Max(240) Integer duration = 999;
        @DecimalMin(value = "0.5", inclusive = false) BigDecimal dose = BigDecimal.ZERO;
        @DecimalMax("10.5") BigDecimal volume = new BigDecimal("11");
        @Positive Integer quantity = 0;
        @PositiveOrZero Integer balance = -1;
        @Negative Integer offset = 1;
        @NegativeOrZero Integer delta = 1;
        @Past LocalDate birthDate = LocalDate.now().plusDays(3);
        @PastOrPresent LocalDate recordedOn = LocalDate.now().plusDays(3);
        @Future LocalDate expiresOn = LocalDate.now().minusDays(3);
        @FutureOrPresent LocalDate dueOn = LocalDate.now().minusDays(3);
        @Pattern(regexp = "\\d+") String code = "abc";
        @Digits(integer = 2, fraction = 1) BigDecimal amount = new BigDecimal("123.45");
        @AssertTrue Boolean attestAccuracy = false;
        @AssertFalse Boolean archived = true;
    }

    /** Fragments of Hibernate Validator's own default messages, in the three languages. */
    private static final List<String> VALIDATOR_WORDING = List.of(
        "ne doit pas", "la taille doit", "supérieur ou égal", "inférieur ou égal", "doit correspondre",
        "doit être une date", "valeur numérique hors limite", "doit être vrai", "doit être faux",
        "must not", "size must", "greater than or equal", "less than or equal", "must match", "must be a past",
        "must be a future", "numeric value out of bounds", "must be true", "must be false",
        "no debe", "el tamaño debe", "o igual a", "debe coincidir",
        "debe ser una fecha", "valor numérico fuera", "debe ser verdadero", "debe ser falso");

    /** How each language quotes the field's label in the house wording. */
    private static final Map<String, String[]> QUOTES = Map.of(
        "fr", new String[] {"« ", " »"}, "en", new String[] {"“", "”"}, "es", new String[] {"«", "»"});

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the sample refuses one field of every house-worded kind")
    void theSampleCoversEveryHouseWordedKind() {
        Set<String> kinds = new TreeSet<>();
        for (Field field : Everything.class.getDeclaredFields()) {
            for (Annotation annotation : field.getDeclaredAnnotations()) {
                kinds.add(annotation.annotationType().getSimpleName());
            }
        }
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(GlobalExceptionHandler.HOUSE_WORDED_CONSTRAINTS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "en", "es"})
    @DisplayName("each refusal names its field in the reader's language, never in the validator's words")
    void everyKindIsWordedByTheHouse(String language) throws Exception {
        Locale locale = Locale.forLanguageTag(language);
        LocaleContextHolder.setLocale(locale);
        var bundles = TestMessageSources.bundles();
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(bundles);

        Map<String, String> reasons = reasons(handler, new Everything());
        assertThat(reasons.keySet()).hasSize(Everything.class.getDeclaredFields().length);
        Map<String, String> failures = new HashMap<>();
        for (Map.Entry<String, String> e : reasons.entrySet()) {
            String label = bundles.getMessage("field.label." + e.getKey(), null, e.getKey(), locale);
            String reason = e.getValue();
            String lower = reason.toLowerCase(locale);
            String[] quote = QUOTES.get(language);
            if (!reason.contains(quote[0] + label + quote[1]) || reason.contains("{") || reason.contains("validation.house")
                    || VALIDATOR_WORDING.stream().anyMatch(lower::contains)) {
                failures.put(e.getKey(), reason);
            }
        }
        assertThat(failures).as("reasons left in the validator's wording (%s)", language).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the limit is in the reason: a length reads its bounds, a list counts items")
    void theLimitIsNamed() throws Exception {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        Map<String, String> reasons = reasons(handler, new Everything());
        assertThat(reasons.get("notes")).startsWith("« notes » : entre 10 et 1").endsWith("024 caractères.");
        assertThat(reasons.get("reason")).isEqualTo("Renseignez « motif ».");
        assertThat(reasons.get("items")).contains("2 éléments au maximum");
        assertThat(reasons.get("minutes")).endsWith(" doit valoir au moins 15.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> reasons(GlobalExceptionHandler handler, Object body) throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(body, "body");
        try (LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            validator.validate(body, result);
        }
        for (FieldError error : result.getFieldErrors()) {
            assertThat(error.contains(jakarta.validation.ConstraintViolation.class)).isTrue();
        }
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/test");
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
            new MethodParameter(Object.class.getMethod("toString"), -1), result);
        Map<String, Object> response = (Map<String, Object>) handler.handleValidationException(ex, request).getBody();
        return (Map<String, String>) response.get("fieldErrors");
    }
}
