package com.example.hms.service.i18n;

import com.example.hms.enums.PatientLanguage;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.repository.SocialHistoryRepository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PatientLocaleResolver")
class PatientLocaleResolverTest {

    private static final Locale FALLBACK = Locale.forLanguageTag("fr");

    private SocialHistoryRepository socialHistoryRepository;
    private PatientLocaleResolver resolver;
    private Patient patient;

    @BeforeEach
    void setUp() {
        socialHistoryRepository = mock(SocialHistoryRepository.class);
        resolver = new PatientLocaleResolver(socialHistoryRepository);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
    }

    private void statedLanguage(String value) {
        PatientSocialHistory history = new PatientSocialHistory();
        history.setPreferredLanguage(value);
        when(socialHistoryRepository
            .findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(any()))
            .thenReturn(Optional.of(history));
    }

    private void noSocialHistory() {
        when(socialHistoryRepository
            .findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(any()))
            .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("writes to a patient in the language they asked for")
    void usesTheStatedLanguage() {
        // THE REGRESSION. This field had been collected since V1 and no
        // dispatch path had ever read it: every reminder went out in one
        // system-wide locale, so a patient who told the desk they read English
        // was sent French anyway and nothing reported it.
        statedLanguage("English");

        assertThat(resolver.resolve(patient, FALLBACK)).isEqualTo(Locale.forLanguageTag("en"));
    }

    @Test
    @DisplayName("falls back when the stated language has no bundle")
    void fallsBackForUndeliverableLanguages() {
        // Nothing can render Moore yet. The fallback still carries the date,
        // the time and the hospital name, which is most of what a reminder is
        // for — sending nothing would be worse.
        statedLanguage("Mooré");

        assertThat(resolver.resolve(patient, FALLBACK)).isEqualTo(FALLBACK);
        // ...but the preference is still reported, which is the only evidence
        // that would justify commissioning the bundle.
        assertThat(resolver.preferredLanguage(patient)).contains(PatientLanguage.MOORE);
    }

    @Test
    @DisplayName("falls back when nothing was stated")
    void fallsBackWithNoPreference() {
        noSocialHistory();
        assertThat(resolver.resolve(patient, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("falls back on an unreadable value rather than guessing")
    void fallsBackOnGibberish() {
        statedLanguage("prefers pictures");
        assertThat(resolver.resolve(patient, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("a blank preference is not a preference")
    void treatsBlankAsAbsent() {
        statedLanguage("   ");
        assertThat(resolver.resolve(patient, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("a null or unsaved patient costs no query")
    void doesNotQueryForAnAbsentPatient() {
        // These run inside best-effort notification sweeps, so a missing
        // patient must be cheap and quiet rather than an exception.
        assertThat(resolver.resolve(null, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(resolver.resolve(new Patient(), FALLBACK)).isEqualTo(FALLBACK);
        verifyNoInteractions(socialHistoryRepository);
    }

    @Test
    @DisplayName("the fallback is the caller's, not one this class invented")
    void honoursEachCallersOwnDefault() {
        // Each sweep already had its own configured locale property. Taking it
        // as an argument means nothing changes for patients who stated no
        // preference — which is almost all of them today.
        noSocialHistory();
        Locale spanishDefault = Locale.forLanguageTag("es");

        assertThat(resolver.resolve(patient, spanishDefault)).isEqualTo(spanishDefault);
    }
}
