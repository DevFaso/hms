package com.example.hms.service.maternity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.WardType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.model.Patient;
import com.example.hms.model.Ward;
import com.example.hms.repository.LaborEpisodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/** QA D3308 (r70): a woman in active labour leaves a maternity ward only knowingly. */
class LabourBedMoveTest {

    private final LaborEpisodeRepository episodes = mock(LaborEpisodeRepository.class);
    private final LabourBedMove rule = new LabourBedMove(episodes);

    private final UUID hospitalId = UUID.randomUUID();
    private Admission admission;
    private Patient patient;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        admission = new Admission();
        admission.setPatient(patient);
        when(episodes.existsByPatient_IdAndHospital_IdAndStatus(patient.getId(), hospitalId, LaborStatus.ACTIVE))
            .thenReturn(true);
    }

    private static Bed bed(WardType type) {
        Bed bed = Bed.builder().ward(type == null ? null : Ward.builder().wardType(type).build()).build();
        bed.setId(UUID.randomUUID());
        return bed;
    }

    @Test
    void aMoveOutOfMaternityNeedsTheAcknowledgementAndOneInsideMaternityDoesNot() {
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.SURGICAL), hospitalId)).isTrue();
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.ICU), hospitalId)).isTrue();
        // A bed with no ward on record is not known to be a maternity bed.
        assertThat(rule.needsAcknowledgement(admission, bed(null), hospitalId)).isTrue();
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.MATERNITY), hospitalId)).isFalse();

        assertThatThrownBy(() -> rule.requireAcknowledged(admission, bed(WardType.SURGICAL), hospitalId, null))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo(LabourBedMove.FIELD));
        assertThatThrownBy(() -> rule.requireAcknowledged(admission, bed(WardType.SURGICAL), hospitalId, false))
            .isInstanceOf(FieldBusinessException.class);
        assertThatCode(() -> rule.requireAcknowledged(admission, bed(WardType.SURGICAL), hospitalId, true))
            .doesNotThrowAnyException();
    }

    @Test
    void aWomanWhoIsNotInLabourAndAnIncompleteCallAskNothing() {
        when(episodes.existsByPatient_IdAndHospital_IdAndStatus(any(), any(), any())).thenReturn(false);
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.SURGICAL), hospitalId)).isFalse();

        assertThat(rule.needsAcknowledgement(null, bed(WardType.SURGICAL), hospitalId)).isFalse();
        assertThat(rule.needsAcknowledgement(admission, null, hospitalId)).isFalse();
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.SURGICAL), null)).isFalse();
        admission.setPatient(null);
        assertThat(rule.needsAcknowledgement(admission, bed(WardType.SURGICAL), hospitalId)).isFalse();
    }

    @Test
    void theBedBoardAsksOnceForEveryOccupant() {
        UUID other = UUID.randomUUID();
        when(episodes.findPatientIdsWithStatusAmong(hospitalId, LaborStatus.ACTIVE, List.of(patient.getId(), other)))
            .thenReturn(List.of(patient.getId()));

        assertThat(rule.inActiveLabour(List.of(patient.getId(), other), hospitalId)).containsExactly(patient.getId());

        LaborEpisodeRepository untouched = mock(LaborEpisodeRepository.class);
        LabourBedMove idle = new LabourBedMove(untouched);
        assertThat(idle.inActiveLabour(List.of(), hospitalId)).isEmpty();
        assertThat(idle.inActiveLabour(null, hospitalId)).isEmpty();
        assertThat(idle.inActiveLabour(List.of(other), null)).isEmpty();
        verifyNoInteractions(untouched);
    }
}
