package com.example.hms.service;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThat;

class PatientRecordPdfImagingHistoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "es"})
    void revisionsKeepTheirKindVersionDateAndUntranslatedClinicalText(String language) {
        var labels = PdfLabels.of(TestMessageSources.bundles(), Locale.forLanguageTag(language));
        var signed = LocalDateTime.of(2026, 11, 15, 10, 20);
        var created = signed.minusDays(1);
        var reports = List.of(
            report("FINAL", 1, signed, created, "Original English clinical text"),
            report("ADDENDUM", 2, signed.plusHours(1), created, "Additional English clinical text"),
            report("CORRECTED", 3, null, created, "Corrected English clinical text"),
            report("AMENDED", 4, null, null, "Amended English clinical text"));
        String text = PatientRecordPdfService.imagingConclusions(
            ChartReviewDTO.ImagingEntryDTO.builder().reports(reports).build(), labels);
        String[] kinds = switch (language) {
            case "fr" -> new String[]{"Définitif", "Addendum", "Corrigé", "Modifié"};
            case "es" -> new String[]{"Definitivo", "Adenda", "Corregido", "Modificado"};
            default -> new String[]{"Final", "Addendum", "Corrected", "Amended"};
        };
        assertThat(text).contains(kinds).contains("v1", "v2", "v3", "v4",
            labels.fmt(signed), labels.fmt(signed.plusHours(1)), labels.fmt(created));
        int previous = -1;
        for (var report : reports) {
            int current = text.indexOf(report.getImpression());
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
        assertThat(text).doesNotContain("null", "pdf.record.");
        assertThat(withoutRandomIds(text.substring(text.indexOf("v4")))).doesNotContain("2026");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "es"})
    void legacyRowsWithoutMetadataKeepTheirTextAndDoNotInventAnIdentity(String language) {
        var labels = PdfLabels.of(TestMessageSources.bundles(), Locale.forLanguageTag(language));
        var entry = ChartReviewDTO.ImagingEntryDTO.builder().reportImpression("Legacy user's words").build();
        String fallback = PatientRecordPdfService.imagingConclusions(entry, labels);
        entry.setReports(List.of(report(null, null, null, null, "Legacy user's words")));
        assertThat(PatientRecordPdfService.imagingConclusions(entry, labels)).isEqualTo(fallback)
            .contains("Legacy user's words");
        assertThat(withoutRandomIds(fallback)).doesNotContain("null", "2026", "()");
    }

    private static ChartReviewDTO.ImagingReportEntryDTO report(String status, Integer version,
        LocalDateTime signed, LocalDateTime created, String text) {
        return ChartReviewDTO.ImagingReportEntryDTO.builder().reportStatus(status).reportVersion(version)
            .signedAt(signed).createdAt(created).impression(text).build();
    }
}
