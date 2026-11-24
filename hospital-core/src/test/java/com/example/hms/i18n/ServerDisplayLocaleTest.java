package com.example.hms.i18n;

import com.example.hms.mapper.InvoiceItemMapper;
import com.example.hms.mapper.NursingNoteMapper;
import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteAddendum;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerDisplayLocaleTest {
    private final MessageSource previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final org.springframework.context.i18n.LocaleContext previousLocale = LocaleContextHolder.getLocaleContext();

    @AfterEach void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.setLocaleContext(previousLocale);
    }

    @ParameterizedTest
    @CsvSource({"fr,Message indisponible.", "en,Message unavailable.", "es,Mensaje no disponible.", "de,Message indisponible."})
    void unavailableBundlesNeverRevealInternalKeysOrArguments(String language, String expected) {
        Locale locale = Locale.forLanguageTag(language);
        for (MessageSource source : new MessageSource[] {null, TestMessageSources.bundles(), brokenSource()}) {
            MessageUtil.setMessageSource(source);
            assertThat(MessageUtil.resolve(locale, "internal.unreleased.key", "sensitive-patient-value"))
                .isEqualTo(expected).doesNotContain("internal", "sensitive", "Missing translation");
            LocaleContextHolder.setLocale(locale);
            assertThat(MessageUtil.resolve("internal.unreleased.key", "sensitive-patient-value")).isEqualTo(expected);
        }
    }

    @Test void previouslyWordedErrorsAndKnownFlatKeysRetainTheirMeaning() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        assertThat(MessageUtil.resolve(Locale.FRENCH,"Dossier introuvable.","private-argument")).isEqualTo("Dossier introuvable.");
        assertThat(MessageUtil.resolve(Locale.ENGLISH,"Encounter not found")).isEqualTo("Encounter not found");
        var source = new org.springframework.context.support.StaticMessageSource();
        source.addMessage("flat_key", Locale.FRENCH,"Libellé traduit");
        MessageUtil.setMessageSource(source);
        assertThat(MessageUtil.resolve(Locale.FRENCH,"flat_key")).isEqualTo("Libellé traduit");
    }

    @ParameterizedTest
    @CsvSource({"fr,Personnel,Auteur inconnu", "en,Staff,Unknown author", "es,Personal,Autor desconocido"})
    void readOnlyMapperFallbacksFollowTheReaderWithoutChangingStoredNames(String language, String staff, String author) {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        assertThat((String) ReflectionTestUtils.invokeMethod(new InvoiceItemMapper(), "buildAssigneeDisplay", new UserRoleHospitalAssignment()))
            .isEqualTo(staff);
        NursingNoteMapper mapper = new NursingNoteMapper();
        NursingNote note = new NursingNote();
        assertThat((String) ReflectionTestUtils.invokeMethod(mapper, "resolveAuthorName", note, null, null)).isEqualTo(author);
        assertThat((String) ReflectionTestUtils.invokeMethod(mapper, "resolveAddendumAuthorName", new NursingNoteAddendum())).isEqualTo(author);
        note.setAuthorName("cdx Nom signé");
        assertThat((String) ReflectionTestUtils.invokeMethod(mapper, "resolveAuthorName", note, null, null)).isEqualTo("cdx Nom signé");
        assertThat(note.getAuthorName()).isEqualTo("cdx Nom signé");
    }

    private static MessageSource brokenSource() {
        MessageSource source = mock(MessageSource.class);
        when(source.getMessage(anyString(), any(), any(Locale.class))).thenThrow(new IllegalStateException("private details"));
        return source;
    }
}
