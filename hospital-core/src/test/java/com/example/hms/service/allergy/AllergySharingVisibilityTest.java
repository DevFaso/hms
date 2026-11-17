package com.example.hms.service.allergy;

import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.enums.RecordAccessDenialReason;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D504 — the withheld flag the allergy list header is built from. */
class AllergySharingVisibilityTest {

    private final PatientAllergyRepository repository = mock(PatientAllergyRepository.class);
    private final RecordAccessPolicy policy = mock(RecordAccessPolicy.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();

    @Test
    void optOutWithoutBreakGlassWithholdsAndReadsOnlyThisHospital() {
        when(repository.hasActiveSharingOptOut(patient)).thenReturn(true);
        when(policy.decide(actor, patient, hospital)).thenReturn(
            RecordAccessDecision.refused(patient, hospital, actor, RecordAccessDenialReason.HOSPITAL_UNKNOWN, null));

        assertThat(AllergySharingVisibility.foreignRowsWithheld(repository, policy, actor, patient, hospital)).isTrue();
        AllergySharingVisibility.rowsVisibleAt(repository, policy, actor, patient, hospital);
        verify(repository).findVisibleAt(patient, hospital);
    }

    @Test
    void breakGlassOverridesTheOptOut() {
        when(repository.hasActiveSharingOptOut(patient)).thenReturn(true);
        when(policy.decide(actor, patient, hospital)).thenReturn(
            RecordAccessDecision.permitted(patient, hospital, actor, null, null));

        assertThat(AllergySharingVisibility.foreignRowsWithheld(repository, policy, actor, patient, hospital)).isFalse();
        AllergySharingVisibility.rowsVisibleAt(repository, policy, actor, patient, hospital);
        verify(repository).findByPatient_Id(patient);
    }

    @Test
    void noOptOutWithholdsNothing() {
        assertThat(AllergySharingVisibility.foreignRowsWithheld(repository, policy, actor, patient, hospital)).isFalse();
    }
}
