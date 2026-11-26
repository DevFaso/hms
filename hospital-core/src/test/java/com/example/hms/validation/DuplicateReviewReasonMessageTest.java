package com.example.hms.validation;

import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.i18n.TestMessageSources;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateReviewReasonMessageTest {
    @ParameterizedTest
    @CsvSource({"en,Shorten", "fr,Raccourcissez", "es,Acorte"})
    void theLengthBoundaryGivesALocalizedActionRatherThanAMissingReason(String language, String action) {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.setValidationMessageSource(TestMessageSources.bundles());
            validator.afterPropertiesSet();
            LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
            var request = new PatientRequestDTO();
            request.setDuplicateReviewReason("x".repeat(2000));
            assertThat(validator.validateProperty(request, "duplicateReviewReason")).isEmpty();
            request.setDuplicateReviewReason("x".repeat(2001));
            var errors = validator.validateProperty(request, "duplicateReviewReason");
            assertThat(errors).hasSize(1);
            assertThat(errors.iterator().next().getMessage()).contains(action, "2000")
                .doesNotContain("{", "reasonRequired", "must not");
        } finally { LocaleContextHolder.resetLocaleContext(); }
    }
}
