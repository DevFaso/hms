package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.UltrasoundMapper;
import com.example.hms.model.UltrasoundReport;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * QA D1525: a finding is charted only when the clinician states it. The one
 * empty-report rule is {@link UltrasoundServiceImpl#requireReportFindings}
 * (QA D1150, on {@code ClinicalContent}); since D1525 nothing defaults an
 * observation, so a stated one (true or false) is a finding.
 */
class UltrasoundReportFindingsTest {

    private static UltrasoundReportRequestDTO.UltrasoundReportRequestDTOBuilder<?, ?> base() {
        return UltrasoundReportRequestDTO.builder().scanDate(LocalDate.now());
    }

    private static boolean stated(UltrasoundReportRequestDTO request) {
        try {
            UltrasoundServiceImpl.requireReportFindings(request);
            return true;
        } catch (FieldBusinessException refused) {
            return false;
        }
    }

    @Test
    void everyObservationNotAssessedAndNothingElseIsAnEmptyReport() {
        UltrasoundReportRequestDTO blank = base()
                .fetalCardiacActivity(null).fetalMovementObserved(null).fetalToneNormal(null)
                .anomaliesDetected(null).anatomySurveyComplete(false).dueDateConfirmed(false)
                .findingCategory(UltrasoundFindingCategory.NORMAL)
                .findingsSummary("   ")
                .build();

        assertThat(stated(blank)).isFalse();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireReportFindings(blank))
                .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("findingsSummary");
                    assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.findingsRequired");
                });
    }

    @Test
    void aStatedObservationIsAFindingWhetherTrueOrFalse() {
        assertThat(stated(base().fetalCardiacActivity(false).build())).as("cardiac activity absent").isTrue();
        assertThat(stated(base().fetalCardiacActivity(true).build())).as("cardiac activity present").isTrue();
        assertThat(stated(base().fetalMovementObserved(false).build())).as("no movement").isTrue();
        assertThat(stated(base().fetalToneNormal(false).build())).as("abnormal tone").isTrue();
        assertThat(stated(base().anomaliesDetected(false).build())).as("no anomalies, stated").isTrue();
        assertThat(stated(base().nasalBonePresent(false).build())).as("nasal bone absent").isTrue();
        assertThat(stated(base().numberOfFetuses(1).build())).as("one fetus, counted").isTrue();
        assertThat(stated(base().numberOfFetuses(0).build())).as("no fetus seen").isTrue();
    }

    @Test
    void aMeasurementOrAWrittenFindingIsAStatement() {
        assertThat(stated(base().fetalHeartRate(150).build())).isTrue();
        assertThat(stated(base().crownRumpLengthMm(55.0).build())).isTrue();
        assertThat(stated(base().interpretation("No cardiac activity").build())).isTrue();
        assertThat(stated(base().gestationalAgeAtScan(12).build())).isTrue();
    }

    @Test
    void theCategoryIsAskedForNotAssumed() {
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireFindingCategory(base().build()))
                .isInstanceOfSatisfying(FieldBusinessException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("findingCategory"));
        assertThatCode(() -> UltrasoundServiceImpl.requireFindingCategory(
                base().findingCategory(UltrasoundFindingCategory.VARIANT).build())).doesNotThrowAnyException();
    }

    @Test
    void normalMayNotContradictAStatedObservation() {
        for (UltrasoundReportRequestDTO r : new UltrasoundReportRequestDTO[] {
                base().fetalCardiacActivity(false).findingCategory(UltrasoundFindingCategory.NORMAL).build(),
                base().fetalToneNormal(false).findingCategory(UltrasoundFindingCategory.NORMAL).build(),
                base().anomaliesDetected(true).findingCategory(UltrasoundFindingCategory.NORMAL).build()}) {
            assertThatThrownBy(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(r))
                    .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                        assertThat(ex.getField()).isEqualTo("findingCategory");
                        assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.normalContradictsObservation");
                    });
        }
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(
                base().fetalCardiacActivity(false).findingCategory(UltrasoundFindingCategory.URGENT).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> UltrasoundServiceImpl.requireCategoryMatchesObservations(
                base().fetalCardiacActivity(true).fetalToneNormal(true).anomaliesDetected(false)
                        .findingCategory(UltrasoundFindingCategory.NORMAL).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void theObservationsStayNotAssessedWhenNobodyStatesThem() {
        UltrasoundReportRequestDTO request = base().fetalHeartRate(150)
                .findingCategory(UltrasoundFindingCategory.NORMAL).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).as("not assessed").isNull();
        assertThat(created.getFetalMovementObserved()).as("not assessed").isNull();
        assertThat(created.getFetalToneNormal()).as("not assessed").isNull();
        assertThat(created.getAnomaliesDetected()).as("not stated").isNull();
        assertThat(created.getNumberOfFetuses()).as("not counted").isNull();
    }

    @Test
    void anAbsentObservationIsKeptAsAbsent() {
        UltrasoundReportRequestDTO request = base().interpretation("No cardiac activity seen")
                .fetalCardiacActivity(false).fetalMovementObserved(false).fetalToneNormal(false)
                .anomaliesDetected(false).findingCategory(UltrasoundFindingCategory.URGENT).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).isFalse();
        assertThat(created.getFetalMovementObserved()).isFalse();
        assertThat(created.getFetalToneNormal()).isFalse();
        assertThat(created.getAnomaliesDetected()).isFalse();
    }

    @Test
    void aNewEntityStatesNothingAndIsLegacyUntilSaved() {
        for (UltrasoundReport r : new UltrasoundReport[] {UltrasoundReport.builder().build(), new UltrasoundReport()}) {
            assertThat(r.getFetalCardiacActivity()).isNull();
            assertThat(r.getFetalMovementObserved()).isNull();
            assertThat(r.getFetalToneNormal()).isNull();
            assertThat(r.getAnomaliesDetected()).isNull();
            assertThat(r.getFindingCategory()).isNull();
            assertThat(r.getNumberOfFetuses()).isNull();
            assertThat(r.getObservationsStated()).isNull();
        }
    }

    @Test
    void theResponseSaysWhetherTheObservationsWereStated() {
        UltrasoundReport legacy = new UltrasoundReport();
        legacy.setFetalCardiacActivity(true);
        UltrasoundReport stated = new UltrasoundReport();
        stated.setObservationsStated(true);
        UltrasoundMapper mapper = new UltrasoundMapper();
        assertThat(mapper.toReportResponseDTO(legacy).getObservationsStated()).isFalse();
        assertThat(mapper.toReportResponseDTO(stated).getObservationsStated()).isTrue();
    }

    /** QA D1525 review B: an edit that sets an observation back to "not assessed" saves null. */
    @Test
    void anEditBackToNotAssessedSavesNull() {
        UltrasoundReport stored = new UltrasoundReport();
        stored.setFetalCardiacActivity(true);
        stored.setFetalMovementObserved(false);
        stored.setFetalToneNormal(true);
        stored.setAnomaliesDetected(false);
        stored.setNumberOfFetuses(2);

        new UltrasoundMapper().updateReportFromRequest(stored,
                base().interpretation("Revu").findingCategory(UltrasoundFindingCategory.VARIANT).build());

        assertThat(stored.getFetalCardiacActivity()).isNull();
        assertThat(stored.getFetalMovementObserved()).isNull();
        assertThat(stored.getFetalToneNormal()).isNull();
        assertThat(stored.getAnomaliesDetected()).isNull();
        assertThat(stored.getNumberOfFetuses()).isNull();
    }

    /** QA D1525 review A: a report with every observation and the fetus count null reads as null, never "no". */
    @Test
    void aReportWithEveryObservationNullReadsAsNull() {
        UltrasoundReport report = new UltrasoundReport();
        report.setObservationsStated(true);
        var dto = new UltrasoundMapper().toReportResponseDTO(report);
        assertThat(dto.getFetalCardiacActivity()).isNull();
        assertThat(dto.getFetalMovementObserved()).isNull();
        assertThat(dto.getFetalToneNormal()).isNull();
        assertThat(dto.getNumberOfFetuses()).isNull();
        assertThat(dto.getAnomaliesDetected()).isNull();
        assertThat(dto.getObservationsStated()).isTrue();
    }

    /** QA D1525 review C: both category refusals resolve from the lab bundle in EN, FR and ES. */
    @Test
    void theCategoryRefusalsResolveInEveryLanguage() {
        for (java.util.Locale locale : new java.util.Locale[] {
                java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es")}) {
            java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("i18n/messages-lab", locale);
            for (String key : new String[] {"ultrasoundReport.findingCategory.required",
                    "ultrasound.report.normalContradictsObservation", "ultrasound.report.findingsRequired"}) {
                assertThat(bundle.getString(key)).as(locale + " " + key).isNotBlank();
            }
        }
        assertThat(java.util.ResourceBundle.getBundle("i18n/messages-lab", java.util.Locale.FRENCH)
                .getString("ultrasound.report.normalContradictsObservation")).contains("NORMAL").contains("catégorie");
    }
}
