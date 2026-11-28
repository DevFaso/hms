package com.example.hms.service;

import com.example.hms.enums.LabQcEventLevel;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabQcEvent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.repository.LabQcEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** r63 D2304: the latest run per control level decides; a passing run clears a rejected one. */
class LabQcReleaseGateTest {

    private LabQcEventRepository repository;
    private LabQcReleaseGate gate;
    private LabOrder order;
    private UUID hospitalId;
    private UUID testId;

    @BeforeEach
    void setUp() {
        repository = mock(LabQcEventRepository.class);
        gate = new LabQcReleaseGate(repository);
        hospitalId = UUID.randomUUID();
        testId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        LabTestDefinition test = new LabTestDefinition();
        test.setId(testId);
        order = new LabOrder();
        order.setHospital(hospital);
        order.setLabTestDefinition(test);
        when(repository.findFirstByHospitalIdAndTestDefinition_IdAndQcLevelOrderByRecordedAtDescCreatedAtDesc(
            any(), any(), any())).thenReturn(Optional.empty());
    }

    private LabQcEvent run(boolean passed) {
        return LabQcEvent.builder().passed(passed).measuredValue(BigDecimal.ONE).expectedValue(BigDecimal.ONE).build();
    }

    private void latest(LabQcEventLevel level, LabQcEvent event) {
        when(repository.findFirstByHospitalIdAndTestDefinition_IdAndQcLevelOrderByRecordedAtDescCreatedAtDesc(
            hospitalId, testId, level)).thenReturn(Optional.of(event));
    }

    @Test
    void aTestWithNoQcRunIsNotBlocked() {
        assertThat(gate.isBlocked(order)).isFalse();
    }

    @Test
    void aRejectedLatestRunAtAnyLevelBlocks() {
        latest(LabQcEventLevel.LOW_CONTROL, run(true));
        LabQcEvent rejected = run(false);
        latest(LabQcEventLevel.HIGH_CONTROL, rejected);

        assertThat(gate.blockingRun(order)).containsSame(rejected);
    }

    /**
     * Review of #1379 (F5): a culture or qualitative test can no longer get a passing QC run, so an old
     * rejected run on it must not hold its results.
     */
    @Test
    void anOldRejectedRunOnACultureOrQualitativeTestNoLongerHoldsResults() {
        latest(LabQcEventLevel.LOW_CONTROL, run(false));
        order.getLabTestDefinition().setExpectedResult("Négatif");
        assertThat(gate.isBlocked(order)).isFalse();

        order.getLabTestDefinition().setExpectedResult(null);
        order.getLabTestDefinition().setCulture(true);
        assertThat(gate.isBlocked(order)).isFalse();

        order.getLabTestDefinition().setCulture(false);
        assertThat(gate.isBlocked(order)).isTrue();
    }

    @Test
    void passingLatestRunsDoNotBlock() {
        latest(LabQcEventLevel.LOW_CONTROL, run(true));
        latest(LabQcEventLevel.HIGH_CONTROL, run(true));

        assertThat(gate.isBlocked(order)).isFalse();
    }

    @Test
    void theRunningLaboratoryIsThePerformingHospitalWhenTheOrderWasSentOut() {
        Hospital performing = new Hospital();
        performing.setId(UUID.randomUUID());
        order.setPerformingHospital(performing);
        // The ordering hospital's own rejected run does not hold another laboratory's work.
        latest(LabQcEventLevel.LOW_CONTROL, run(false));

        assertThat(gate.isBlocked(order)).isFalse();
    }

    @Test
    void anOrderWithoutATestIsNotBlocked() {
        assertThat(gate.isBlocked(null)).isFalse();
        order.setLabTestDefinition(null);
        assertThat(gate.isBlocked(order)).isFalse();
    }
}
