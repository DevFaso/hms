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
 * (QA D1150/D1182, on {@code ClinicalContent}).
 */
class UltrasoundReportFindingsTest {

    private static boolean stated(UltrasoundReportRequestDTO request) {
        try {
            UltrasoundServiceImpl.requireReportFindings(request);
            return true;
        } catch (FieldBusinessException refused) {
            return false;
        }
    }

    private static UltrasoundReportRequestDTO.UltrasoundReportRequestDTOBuilder<?, ?> base() {
        return UltrasoundReportRequestDTO.builder().scanDate(LocalDate.now());
    }

    @Test
    void theOldTemplateIsAnEmptyReport() {
        // What the nuchal-translucency template used to send, minus the gestational
        // age it pre-filled: the boxes, NORMAL and one fetus state nothing.
        UltrasoundReportRequestDTO oldTemplate = base()
                .numberOfFetuses(1)
                .fetalCardiacActivity(true).fetalMovementObserved(true).fetalToneNormal(true)
                .nasalBonePresent(true).anatomySurveyComplete(true).dueDateConfirmed(true)
                .findingCategory(UltrasoundFindingCategory.NORMAL)
                .findingsSummary("   ")
                .build();

        assertThat(stated(oldTemplate)).isFalse();
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireReportFindings(oldTemplate))
                .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("findingsSummary");
                    assertThat(ex.getMessageKey()).isEqualTo("ultrasound.report.findingsRequired");
                });
    }

    @Test
    void aMeasurementOrAWrittenFindingIsAStatement() {
        assertThat(stated(base().fetalHeartRate(150).build())).isTrue();
        assertThat(stated(base().crownRumpLengthMm(55.0).build())).isTrue();
        assertThat(stated(base().interpretation("No cardiac activity").build())).isTrue();
        assertThat(stated(base().gestationalAgeAtScan(12).build()))
                .as("a gestational age typed by the clinician (templates no longer fill it)").isTrue();
        assertThat(stated(base().numberOfFetuses(2).build()))
                .as("twins are a finding").isTrue();
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
    void theFlagsStayNotAssessedWhenNobodyStatesThem() {
        UltrasoundReportRequestDTO request = base().fetalHeartRate(150)
                .findingCategory(UltrasoundFindingCategory.NORMAL).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).as("not assessed").isNull();
        assertThat(created.getFetalMovementObserved()).as("not assessed").isNull();
        assertThat(created.getFetalToneNormal()).as("not assessed").isNull();
        assertThat(created.getNumberOfFetuses()).as("not counted").isNull();
    }

    @Test
    void anAbsentFlagIsKeptAsAbsent() {
        UltrasoundReportRequestDTO request = base().interpretation("No cardiac activity seen")
                .fetalCardiacActivity(false).fetalMovementObserved(false).fetalToneNormal(false)
                .findingCategory(UltrasoundFindingCategory.URGENT).build();

        UltrasoundReport created = new UltrasoundMapper().toReportEntity(request, null, null);

        assertThat(created.getFetalCardiacActivity()).isFalse();
        assertThat(created.getFetalMovementObserved()).isFalse();
        assertThat(created.getFetalToneNormal()).isFalse();
    }

    @Test
    void aNewEntityStatesNothing() {
        UltrasoundReport built = UltrasoundReport.builder().build();
        UltrasoundReport constructed = new UltrasoundReport();
        for (UltrasoundReport r : new UltrasoundReport[] {built, constructed}) {
            assertThat(r.getFetalCardiacActivity()).isNull();
            assertThat(r.getFetalMovementObserved()).isNull();
            assertThat(r.getFetalToneNormal()).isNull();
            assertThat(r.getFindingCategory()).isNull();
            assertThat(r.getNumberOfFetuses()).isNull();
        }
    }
}
