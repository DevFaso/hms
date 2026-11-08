package com.example.hms.service.support;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * QA D1538 review (round 40): an obstetric order placed as an emergency for a patient the rule would
 * refuse is audited: the rule, the reason, the priority, the recorded sex and age at the time.
 */
class ObstetricRuleSkipRecorderTest {

    @Test
    @SuppressWarnings("unchecked")
    void theSkipIsAuditedWithTheRecordedSexAndAge() {
        AuditEventLogService audit = mock(AuditEventLogService.class);
        ObstetricRuleSkipRecorder recorder = new ObstetricRuleSkipRecorder(audit);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setGender("M");
        patient.setDateOfBirth(LocalDate.now().minusYears(56).minusDays(1));
        UUID orderId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        // No transaction here: written at once, as after a commit.
        recorder.record(patient, "STAT", "ULTRASOUND_ORDER", orderId, hospitalId);

        ArgumentCaptor<AuditEventRequestDTO> written = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(written.capture());
        AuditEventRequestDTO row = written.getValue();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.OBSTETRIC_RULE_SKIPPED_EMERGENCY);
        assertThat(row.getPatientId()).isEqualTo(patient.getId());
        assertThat(row.getHospitalId()).isEqualTo(hospitalId);
        assertThat(row.getResourceId()).isEqualTo(orderId.toString());
        assertThat(row.getEntityType()).isEqualTo("ULTRASOUND_ORDER");
        Map<String, Object> details = (Map<String, Object>) row.getDetails();
        assertThat(details)
            .containsEntry("rule", ObstetricRuleSkipRecorder.RULE)
            .containsEntry("skippedBecause", "EMERGENCY")
            .containsEntry("priority", "STAT")
            .containsEntry("recordedSex", "MALE")
            .containsEntry("recordedAgeYears", 56)
            .containsEntry("recordedMale", true)
            .containsEntry("recordedChild", false);
    }

    /** QA D1538 review: a flag cleared by an edit is audited too, under the same event. */
    @Test
    @SuppressWarnings("unchecked")
    void theClearingIsAudited() {
        AuditEventLogService audit = mock(AuditEventLogService.class);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setGender("F");
        new ObstetricRuleSkipRecorder(audit).recordCleared(patient, "STAT", "ULTRASOUND_ORDER", UUID.randomUUID(), null);

        ArgumentCaptor<AuditEventRequestDTO> written = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(written.capture());
        assertThat(written.getValue().getEventType()).isEqualTo(AuditEventType.OBSTETRIC_RULE_SKIPPED_EMERGENCY);
        assertThat(written.getValue().getEventDescription()).isEqualTo("Obstetric rule skip cleared on an ultrasound order");
        assertThat((Map<String, Object>) written.getValue().getDetails()).containsEntry("cleared", true);
    }

    @Test
    void aChildsAgeIsRecorded() {
        Patient child = new Patient();
        child.setGender("F");
        child.setDateOfBirth(LocalDate.now().minusYears(6).minusDays(1));
        assertThat(ObstetricRuleSkipRecorder.details(child, "URGENT"))
            .containsEntry("recordedSex", "F")
            .containsEntry("recordedAgeYears", 6)
            .containsEntry("recordedChild", true);
    }
}
