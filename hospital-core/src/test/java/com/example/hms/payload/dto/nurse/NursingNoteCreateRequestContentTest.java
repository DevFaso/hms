package com.example.hms.payload.dto.nurse;

import com.example.hms.validation.ReportedOn;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.NursingNoteTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3860 (r77, r81): a DAR nursing note with no data, action, response or
 * narrative was accepted (201) and listed on the chart as an empty note. A
 * note needs one written part. Its attestations and its signature are not
 * content.
 */
@DisplayName("D3860: a nursing note with nothing written is refused at the edge")
class NursingNoteCreateRequestContentTest {

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static NursingNoteCreateRequestDTO.NursingNoteCreateRequestDTOBuilder attested(NursingNoteTemplate template) {
        return NursingNoteCreateRequestDTO.builder()
            .patientId(UUID.randomUUID())
            .template(template)
            .attestAccuracy(true)
            .attestSpellCheck(true)
            .attestNoAbbreviations(true)
            .signedByName("Awa Ouédraogo");
    }

    private static List<String> refusals(NursingNoteCreateRequestDTO body) {
        try (LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()) {
            validator.setValidationMessageSource(TestMessageSources.bundles());
            validator.afterPropertiesSet();
            LocaleContextHolder.setLocale(Locale.FRENCH);
            return validator.validate(body).stream()
                .map(v -> reportedOn(v.getPropertyPath().toString()) + " = " + v.getMessage())
                .toList();
        }
    }

    /** The JSON field a rule is reported on, as the handler reads {@link ReportedOn}. */
    private static String reportedOn(String property) {
        try {
            String getter = "is" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            ReportedOn on = NursingNoteCreateRequestDTO.class.getMethod(getter).getAnnotation(ReportedOn.class);
            return on == null ? property : on.value();
        } catch (NoSuchMethodException e) {
            return property;
        }
    }

    @ParameterizedTest
    @EnumSource(NursingNoteTemplate.class)
    @DisplayName("an attested, signed note with blank parts is refused on dataSubjective")
    void anEmptyNoteIsRefused(NursingNoteTemplate template) {
        List<String> refusals = refusals(attested(template).dataSubjective("   ").narrative("").build());
        assertThat(refusals).containsExactly(
            "dataSubjective = " + TestMessageSources.bundles().getMessage("nursingNote.contentRequired", null, Locale.FRENCH));
    }

    @Test
    @DisplayName("one written part, or one real entry, is enough")
    void oneWrittenPartIsEnough() {
        assertThat(refusals(attested(NursingNoteTemplate.DAR).actionSummary("Pansement refait.").build())).isEmpty();
        assertThat(refusals(attested(NursingNoteTemplate.SOAPIE).dataPlan("Surveillance 2 h.").build())).isEmpty();
        assertThat(refusals(attested(NursingNoteTemplate.DAR)
            .educationEntries(List.of(NursingNoteEducationDTO.builder()
                .topic("Hygiène").patientUnderstanding("Comprend").build())).build())).isEmpty();
    }
}
