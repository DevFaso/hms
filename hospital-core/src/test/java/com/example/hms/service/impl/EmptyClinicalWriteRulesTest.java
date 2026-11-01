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
        UltrasoundReportRequestDTO noActivity = new UltrasoundReportRequestDTO();
        noActivity.setFetalCardiacActivity(Boolean.FALSE);
        assertThatCode(() -> UltrasoundServiceImpl.requireReportFindings(noActivity))
            .as("a deliberate 'no cardiac activity' is a finding").doesNotThrowAnyException();
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
        assertThatThrownBy(() -> AdvanceDirectiveServiceImpl.requireOrderDetails(dnr))
            .extracting("messageKey").isEqualTo("advanceDirective.physician.required");
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
