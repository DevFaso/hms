package com.example.hms.service;

import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteTemplate;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.service.impl.ChartReviewServiceImpl;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 19: server-composed chart text.
 * <ul>
 *   <li>QA D1079 (the backend half): the chronology joined a lab label and its
 *       value with a glued colon ("Glycémie à jeun: 5.6 g/L"); French writes
 *       a space before the colon.</li>
 *   <li>QA D1107: a nursing note reached the chart review without its title,
 *       and unsigned, so doctors read it as "Brouillon".</li>
 * </ul>
 */
class Round19ChartTextTest {

    private MessageSource previous;

    @BeforeEach
    void bundles() {
        previous = (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void restore() {
        LocaleContextHolder.resetLocaleContext();
        MessageUtil.setMessageSource(previous);
    }

    @Test
    void d1079_theLabSummaryColonFollowsTheReadersTypography() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(PatientServiceImpl.labelValue("Glycémie à jeun", "5.6")).isEqualTo("Glycémie à jeun : 5.6");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(PatientServiceImpl.labelValue("Fasting glucose", "5.6")).isEqualTo("Fasting glucose: 5.6");
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        assertThat(PatientServiceImpl.labelValue("Glucemia", "5.6")).isEqualTo("Glucemia: 5.6");
    }

    @Test
    void d1107_aNursingNoteKeepsItsTitleAndItsSignature() {
        NursingNote note = new NursingNote();
        note.setId(UUID.randomUUID());
        note.setTemplate(NursingNoteTemplate.DAR);
        note.setTitle("  Réévaluation douleur ");
        note.setAuthorName("Awa Nurse");
        note.setDataSubjective("Douleur 3/10");
        LocalDateTime at = LocalDateTime.of(2026, 10, 31, 9, 0);
        note.setDocumentedAt(at);
        note.setSignedAt(at);
        note.setSignedByName("Awa Nurse");

        ChartReviewDTO.NoteEntryDTO dto = ChartReviewServiceImpl.toNursingNoteDto(note);

        assertThat(dto.getTitle()).isEqualTo("Réévaluation douleur");
        assertThat(dto.isSigned()).isTrue();
        assertThat(dto.getPreview()).isEqualTo("Douleur 3/10");
    }
}
