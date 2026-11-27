package com.example.hms.service.lab;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LabOrderCompletionTest {
    @Test void bothKindsOfReportMustBeReleasedAndThereMustBeAResult() {
        LabResult ordinary = LabResult.builder().released(false).build();
        ordinary.setId(java.util.UUID.randomUUID());
        assertThat(LabOrderCompletion.allReleased(List.of(), true, false)).isFalse();
        assertThat(LabOrderCompletion.allReleased(List.of(), true, true)).isTrue();
        assertThat(LabOrderCompletion.allReleased(List.of(ordinary), true, true)).isFalse();
        ordinary.setReleased(true);
        assertThat(LabOrderCompletion.allReleased(List.of(ordinary), false, false)).isFalse();
        assertThat(LabOrderCompletion.allReleased(List.of(ordinary), true, false)).isTrue();
    }
}
