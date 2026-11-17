package com.example.hms.service;

import com.example.hms.enums.TreatmentPlanStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.encounter.EncounterNote;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1151 and D1162 (round 21): an empty note is refused; an edited approved plan goes back to review. */
class ClinicalRecordRulesR21Test {

    @Test
    void anEmptyEncounterNoteIsRefused_D1151() {
        EncounterNote empty = new EncounterNote();
        empty.setSubjective("   ");
        assertThatThrownBy(() -> EncounterServiceImpl.requireNoteContent(empty))
            .isInstanceOf(FieldBusinessException.class)
            .extracting("messageKey").isEqualTo("encounter.note.contentRequired");

        EncounterNote written = new EncounterNote();
        written.setAssessment("Paludisme simple");
        assertThatCode(() -> EncounterServiceImpl.requireNoteContent(written)).doesNotThrowAnyException();
    }

    @Test
    void anEditedApprovedPlanGoesBackToReview_D1162() {
        assertThat(TreatmentPlanServiceImpl.statusAfterEdit(TreatmentPlanStatus.APPROVED, true, TreatmentPlanStatus.APPROVED))
            .isEqualTo(TreatmentPlanStatus.IN_REVIEW);
        assertThat(TreatmentPlanServiceImpl.statusAfterEdit(TreatmentPlanStatus.APPROVED, false, TreatmentPlanStatus.APPROVED))
            .as("an echo of the approved content keeps the approval").isEqualTo(TreatmentPlanStatus.APPROVED);
        assertThat(TreatmentPlanServiceImpl.statusAfterEdit(TreatmentPlanStatus.APPROVED, true, TreatmentPlanStatus.ARCHIVED))
            .isEqualTo(TreatmentPlanStatus.ARCHIVED);
        assertThat(TreatmentPlanServiceImpl.statusAfterEdit(TreatmentPlanStatus.DRAFT, true, TreatmentPlanStatus.DRAFT))
            .isEqualTo(TreatmentPlanStatus.DRAFT);
    }
}
