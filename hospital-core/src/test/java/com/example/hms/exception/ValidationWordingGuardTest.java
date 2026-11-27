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
        @jakarta.validation.constraints.Max(10) public int maximum = 11;
        @jakarta.validation.constraints.DecimalMin(value = "2.5", inclusive = false) public java.math.BigDecimal above = new java.math.BigDecimal("2.5");
        @jakarta.validation.constraints.DecimalMax(value = "4.5", inclusive = false) public java.math.BigDecimal below = new java.math.BigDecimal("4.5");
        @NotBlank(message = "custom instruction") public String name = "";
    }
    /** One field per house-worded constraint, each left on its default message and violated. */
    static class EveryConstraint {
        @NotNull public String notNull;
        @NotBlank public String notBlank = " ";
        @jakarta.validation.constraints.NotEmpty public String notEmpty = "";
        @jakarta.validation.constraints.Null public String mustBeNull = "x";
        @jakarta.validation.constraints.AssertTrue public boolean assertTrue = false;
        @jakarta.validation.constraints.AssertFalse public boolean assertFalse = true;
        @Min(5) public int min = 1;
        @jakarta.validation.constraints.Max(5) public int max = 9;
        @jakarta.validation.constraints.DecimalMin("2.5") public java.math.BigDecimal decimalMin = java.math.BigDecimal.ONE;
        @jakarta.validation.constraints.DecimalMax("2.5") public java.math.BigDecimal decimalMax = java.math.BigDecimal.TEN;
        @jakarta.validation.constraints.Positive public int positive = 0;
        @jakarta.validation.constraints.PositiveOrZero public int positiveOrZero = -1;
        @jakarta.validation.constraints.Negative public int negative = 0;
        @jakarta.validation.constraints.NegativeOrZero public int negativeOrZero = 1;
        @jakarta.validation.constraints.Size(max = 3) public String size = "abcd";
        @jakarta.validation.constraints.Size(min = 2) public java.util.List<String> sizeItems = java.util.List.of("a");
        @jakarta.validation.constraints.Digits(integer = 2, fraction = 1) public java.math.BigDecimal digits = new java.math.BigDecimal("123.45");
        @jakarta.validation.constraints.Pattern(regexp = "[0-9]+") public String pattern = "abc";
        @jakarta.validation.constraints.Email public String email = "not an address";
        @jakarta.validation.constraints.Past public java.time.LocalDate past = java.time.LocalDate.now().plusDays(2);
        @jakarta.validation.constraints.PastOrPresent public java.time.LocalDate pastOrPresent = java.time.LocalDate.now().plusDays(2);
        @jakarta.validation.constraints.Future public java.time.LocalDate future = java.time.LocalDate.now().minusDays(2);
        @jakarta.validation.constraints.FutureOrPresent public java.time.LocalDate futureOrPresent = java.time.LocalDate.now().minusDays(2);
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
                String required = locale.equals(Locale.FRENCH) ? "veuillez renseigner" : "please enter";
                String minimum = locale.equals(Locale.FRENCH) ? "au moins" : "at least";
                assertThat((String) fields.get("reason")).contains(required).doesNotContain("ne doit pas");
                assertThat((String) fields.get("hospitalId")).contains(required);
                assertThat((String) fields.get("quantity")).contains(minimum, "1");
                assertThat((String) fields.get("maximum")).contains("10", locale.equals(Locale.FRENCH) ? "inférieur ou égal" : "at most");
                assertThat((String) fields.get("above")).contains(locale.equals(Locale.FRENCH) || "es".equals(locale.getLanguage()) ? "2,5" : "2.5", locale.equals(Locale.FRENCH) ? "supérieur à" : "greater than");
                assertThat((String) fields.get("below")).contains(locale.equals(Locale.FRENCH) || "es".equals(locale.getLanguage()) ? "4,5" : "4.5", locale.equals(Locale.FRENCH) ? "inférieur à" : "less than");
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

    private static final java.util.regex.Pattern HIBERNATE_CONSTRAINT_USE = java.util.regex.Pattern.compile(
        "org\\.hibernate\\.validator\\.constraints\\.([A-Z][A-Za-z]+)");

    /** Every built-in bean-validation constraint on the classpath (NotNull, Size, …). */
    private static java.util.Set<String> builtInConstraints() throws java.io.IOException {
        java.util.Set<String> names = new java.util.TreeSet<>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:jakarta/validation/constraints/*.class")) {
            String file = resource.getFilename();
            if (file != null && !file.contains("$")) names.add(file.substring(0, file.length() - ".class".length()));
        }
        return names;
    }

    /**
     * QA D4158 class guard: every bean-validation constraint the code uses
     * must have a house wording, so a new one cannot bring back the raw
     * validator phrase (« … : la taille doit être comprise entre … »).
     */
    @Test
    void everyConstraintUsedInTheCodeHasHouseWording() throws Exception {
        java.util.Set<String> used = new java.util.TreeSet<>();
        java.util.Set<String> hibernate = new java.util.TreeSet<>();
        java.util.Set<String> builtIn = builtInConstraints();
        assertThat(builtIn).as("built-in constraints on the classpath").contains("NotNull", "Size", "Digits");
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file);
                if (source.contains("jakarta.validation.constraints")) {
                    for (String name : builtIn) {
                        if (java.util.regex.Pattern.compile("@(?:jakarta\\.validation\\.constraints\\.)?" + name + "\\b")
                                .matcher(source).find()) {
                            used.add(name);
                        }
                    }
                }
                var h = HIBERNATE_CONSTRAINT_USE.matcher(source);
                while (h.find()) hibernate.add(h.group(1) + " in " + file.getFileName());
            }
        }
        assertThat(used).as("constraints found in src/main/java").contains("NotNull", "Size");
        assertThat(GlobalExceptionHandler.HOUSE_WORDED_CONSTRAINTS)
            .as("a constraint used in the code has no house wording in GlobalExceptionHandler")
            .containsAll(used);
        assertThat(hibernate).as("Hibernate-specific constraints have no house wording").isEmpty();
        java.util.Set<String> covered = new java.util.TreeSet<>();
        for (var field : EveryConstraint.class.getDeclaredFields()) {
            for (var annotation : field.getAnnotations()) covered.add(annotation.annotationType().getSimpleName());
        }
        assertThat(covered).as("EveryConstraint exercises each house-worded constraint")
            .containsAll(GlobalExceptionHandler.HOUSE_WORDED_CONSTRAINTS);
    }

    @Test
    void everyHouseWordedConstraintAnswersWithoutTheValidatorPhrase() {
        var handler = new GlobalExceptionHandler();
        handler.setMessageSource(TestMessageSources.bundles());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (Locale locale : new Locale[] { Locale.FRENCH, Locale.ENGLISH }) {
                LocaleContextHolder.setLocale(locale);
                var violations = factory.getValidator().validate(new EveryConstraint());
                assertThat(violations).hasSize(EveryConstraint.class.getDeclaredFields().length);
                var body = (Map<?, ?>) handler.handleConstraintViolation(new ConstraintViolationException(violations),
                    new ServletWebRequest(new MockHttpServletRequest())).getBody();
                var fields = (Map<?, ?>) body.get("fieldErrors");
                for (var violation : violations) {
                    String reason = (String) fields.get(violation.getPropertyPath().toString());
                    assertThat(reason).as(violation.getPropertyPath() + " in " + locale)
                        .isNotBlank().doesNotContain(": " + violation.getMessage()).doesNotContain("{");
                }
                if (locale.equals(Locale.FRENCH)) {
                    assertThat((String) fields.get("size")).contains("3 caractères au maximum");
                    assertThat((String) fields.get("sizeItems")).contains("au moins 2 éléments");
                    assertThat((String) fields.get("digits")).contains("2 chiffres", "1 après");
                    assertThat((String) fields.get("positiveOrZero")).contains("au moins égal à 0");
                    assertThat((String) fields.get("pastOrPresent")).contains("ne peut pas être dans le futur");
                }
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /** QA D4158: the emergency release names the missing reason in its own sentence. */
    @Test
    void emergencyReleaseReasonUsesItsOwnSentence() throws Exception {
        var annotation = com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO.class
            .getDeclaredField("reason").getAnnotation(NotBlank.class);
        assertThat(annotation.message()).isEqualTo("{transfusion.emergency.reasonRequired}");
        assertThat(TestMessageSources.bundles().getMessage("transfusion.emergency.reasonRequired", null, Locale.FRENCH))
            .startsWith("Indiquez le motif");
    }
}
