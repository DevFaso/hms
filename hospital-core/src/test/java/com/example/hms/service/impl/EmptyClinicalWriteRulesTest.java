package com.example.hms.service.impl;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.enums.UltrasoundFindingCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.AdvanceDirective;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.ultrasound.UltrasoundReportRequestDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA D1148 / D1150 / D1151 / D1159 (round 21): the server refuses a clinical
 * write with nothing in it, and a dose held or refused without a reason.
 */
class EmptyClinicalWriteRulesTest {

    /**
     * QA D1182 (round 22) said the yes/no boxes were not findings because the
     * form pre-filled them. QA D1525 removed every such default (no template,
     * no entity or mapper TRUE), so a stated observation is a finding; what is
     * still refused is a report whose every answer is "not assessed" and that
     * states nothing else, whatever its category and date.
     */
    @Test
    void aReportWhoseEveryObservationIsNotAssessedIsBlank_D1182_D1525() {
        UltrasoundReportRequestDTO blank = new UltrasoundReportRequestDTO();
        blank.setScanDate(LocalDate.of(2026, 11, 1));
        blank.setFindingCategory(UltrasoundFindingCategory.NORMAL);
        blank.setFetalCardiacActivity(null);
        blank.setFetalMovementObserved(null);
        blank.setFetalToneNormal(null);
        blank.setAnomaliesDetected(null);
        blank.setFindingsSummary("   ");
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireReportFindings(blank))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("ultrasound.report.findingsRequired");
    }

    /** QA D1182 class (round 22): a maternal-history edit writes a new version from its body alone. */
    @Test
    void aBlankMaternalHistoryEditIsRefused_D1182() {
        com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO blank =
            new com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO();
        blank.setPatientId(java.util.UUID.randomUUID());
        blank.setHospitalId(java.util.UUID.randomUUID());
        blank.setUpdateReason("correction");
        assertThatThrownBy(() -> MaternalHistoryServiceImpl.requireHistoryContent(blank))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("maternalHistory.contentRequired");
    }

    @Test
    void aBlankUltrasoundReportIsRefusedEvenWithACategory_D1150() {
        UltrasoundReportRequestDTO blank = new UltrasoundReportRequestDTO();
        blank.setScanDate(LocalDate.of(2026, 10, 31));
        blank.setFindingCategory(UltrasoundFindingCategory.NORMAL);
        assertThatThrownBy(() -> UltrasoundServiceImpl.requireReportFindings(blank))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("ultrasound.report.findingsRequired");

        UltrasoundReportRequestDTO filled = new UltrasoundReportRequestDTO();
        filled.setFetalHeartRate(142);
        assertThatCode(() -> UltrasoundServiceImpl.requireReportFindings(filled)).doesNotThrowAnyException();
        // QA D1525 (changed assertion again): nothing defaults the box any
        // more, so a stated "no cardiac activity" is a finding on its own.
        UltrasoundReportRequestDTO noActivity = new UltrasoundReportRequestDTO();
        noActivity.setFetalCardiacActivity(Boolean.FALSE);
        assertThatCode(() -> UltrasoundServiceImpl.requireReportFindings(noActivity)).doesNotThrowAnyException();
    }

    @Test
    void aBlankPostpartumObservationIsRefusedAndAnEducationNoteIsNotACheck_D1151() {
        PostpartumObservationRequestDTO blank = new PostpartumObservationRequestDTO();
        assertThatThrownBy(() -> PostpartumCareServiceImpl.requireObservationFindings(blank))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("maternity.postpartum.findingsRequired");
        assertThat(PostpartumCareServiceImpl.isMonitoringCheck(blank)).isFalse();

        PostpartumObservationRequestDTO education = new PostpartumObservationRequestDTO();
        education.setEducationNotes("Allaitement expliqué");
        assertThatCode(() -> PostpartumCareServiceImpl.requireObservationFindings(education)).doesNotThrowAnyException();
        assertThat(PostpartumCareServiceImpl.isMonitoringCheck(education)).isFalse();

        PostpartumObservationRequestDTO vitals = new PostpartumObservationRequestDTO();
        vitals.setPulseBpm(96);
        assertThat(PostpartumCareServiceImpl.isMonitoringCheck(vitals)).isTrue();
    }

    @Test
    void cr05_aDangerSignLookedAtIsAFinding_notAssessedIsNot() {
        NewbornAssessmentRequestDTO notAssessed = new NewbornAssessmentRequestDTO();
        notAssessed.setConvulsions(com.example.hms.enums.NewbornDangerSignObservation.NOT_ASSESSED);
        assertThatThrownBy(() -> NewbornAssessmentServiceImpl.requireAssessmentFindings(notAssessed))
            .extracting("messageKey").isEqualTo("maternity.newborn.findingsRequired");
        for (com.example.hms.enums.NewbornDangerSignObservation looked : java.util.List.of(
                com.example.hms.enums.NewbornDangerSignObservation.PRESENT, com.example.hms.enums.NewbornDangerSignObservation.ABSENT)) {
            NewbornAssessmentRequestDTO one = new NewbornAssessmentRequestDTO();
            one.setSevereChestIndrawing(looked);
            assertThatCode(() -> NewbornAssessmentServiceImpl.requireAssessmentFindings(one)).doesNotThrowAnyException();
        }
    }

    @Test
    void aBlankNewbornAssessmentIsRefused_D1151() {
        NewbornAssessmentRequestDTO blank = new NewbornAssessmentRequestDTO();
        assertThatThrownBy(() -> NewbornAssessmentServiceImpl.requireAssessmentFindings(blank))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("maternity.newborn.findingsRequired");
        NewbornAssessmentRequestDTO apgar = new NewbornAssessmentRequestDTO();
        apgar.setApgarOneMinute(8);
        assertThatCode(() -> NewbornAssessmentServiceImpl.requireAssessmentFindings(apgar)).doesNotThrowAnyException();
    }

    @Test
    void anActiveDnrNamesItsPhysicianAndDate_D1148() {
        AdvanceDirective dnr = new AdvanceDirective();
        dnr.setDirectiveType(AdvanceDirectiveType.DO_NOT_RESUSCITATE);
        dnr.setStatus(AdvanceDirectiveStatus.ACTIVE);
        // QA D1355 (round 30): both missing fields are named in one refusal.
        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr))
            .isInstanceOf(FieldBusinessException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((FieldBusinessException) e).getFieldErrors())
                .containsOnlyKeys("physicianName", "effectiveDate"));
        dnr.setEffectiveDate(LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr))
            .extracting("messageKey").isEqualTo("advanceDirective.physician.required");
        dnr.setEffectiveDate(null);
        dnr.setPhysicianName("Dr Ouédraogo");
        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr))
            .extracting("messageKey").isEqualTo("advanceDirective.effectiveDate.required");
        dnr.setEffectiveDate(LocalDate.of(2026, 10, 31));
        assertThatCode(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr)).doesNotThrowAnyException();

        AdvanceDirective will = new AdvanceDirective();
        will.setDirectiveType(AdvanceDirectiveType.LIVING_WILL);
        will.setStatus(AdvanceDirectiveStatus.ACTIVE);
        assertThatCode(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(will)).doesNotThrowAnyException();
    }

    @Test
    void aHeldOrRefusedDoseSaysWhy_D1159() {
        for (MedicationAdministrationStatus status : new MedicationAdministrationStatus[] {
                MedicationAdministrationStatus.HELD, MedicationAdministrationStatus.REFUSED}) {
            for (String blank : new String[] {null, "", "  "}) {
                assertThatThrownBy(() -> NurseTaskServiceImpl.requireReasonForWithheldDose(status, blank))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("nurse.mar.reasonRequired");
            }
            assertThatCode(() -> NurseTaskServiceImpl.requireReasonForWithheldDose(status, "Patient vomiting"))
                .doesNotThrowAnyException();
        }
        assertThatCode(() -> NurseTaskServiceImpl.requireReasonForWithheldDose(
            MedicationAdministrationStatus.GIVEN, null)).doesNotThrowAnyException();
    }
}
