package com.example.hms.mapper;

import com.example.hms.model.LabResult;
import com.example.hms.payload.dto.LabResultResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2952 (r66): a refused read-back left "read back by" and the repeated value on a result that stayed
 * unacknowledged, so the record read as if the critical value had been read back. The refusal is still kept (a
 * clinician repeating the wrong value is the event the audit trail is for), under its own fields.
 */
@DisplayName("A refused read-back is never shown as a read-back (QA D2952)")
class LabResultMapperReadBackTest {

    private final LabResultMapper mapper = new LabResultMapper();

    private static LabResult stored(String value, String by, LocalDateTime confirmedAt) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setCriticalReadBackValue(value);
        result.setCriticalReadBackByDisplay(by);
        result.setCriticalReadBackByUserId(UUID.randomUUID());
        result.setCriticalReadBackAt(confirmedAt);
        return result;
    }

    @Test
    @DisplayName("refused: no read-back author, no read-back value; the refusal is told apart")
    void aRefusedReadBackNamesNoReaderAndNoValue() {
        LabResultResponseDTO dto = mapper.toResponseDTO(stored("5,6", "Test Médecin 6", null));

        assertThat(dto.isAcknowledged()).isFalse();
        assertThat(dto.getCriticalReadBackAt()).isNull();
        assertThat(dto.getCriticalReadBackBy()).isNull();
        assertThat(dto.getCriticalReadBackValue()).isNull();
        assertThat(dto.getCriticalReadBackRefusedValue()).isEqualTo("5,6");
        assertThat(dto.getCriticalReadBackRefusedBy()).isEqualTo("Test Médecin 6");
    }

    @Test
    @DisplayName("confirmed: the reader and the value are the read-back; nothing is shown as refused")
    void aConfirmedReadBackNamesItsReaderAndValue() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 20, 23, 50);
        LabResultResponseDTO dto = mapper.toResponseDTO(stored("5,5", "Test Médecin 6", at));

        assertThat(dto.getCriticalReadBackAt()).isEqualTo(at);
        assertThat(dto.getCriticalReadBackBy()).isEqualTo("Test Médecin 6");
        assertThat(dto.getCriticalReadBackValue()).isEqualTo("5,5");
        assertThat(dto.getCriticalReadBackRefusedValue()).isNull();
        assertThat(dto.getCriticalReadBackRefusedBy()).isNull();
    }

    @Test
    @DisplayName("never read back: every read-back field is empty")
    void aResultNeverReadBackShowsNothing() {
        LabResultResponseDTO dto = mapper.toResponseDTO(stored(null, null, null));

        assertThat(dto.getCriticalReadBackBy()).isNull();
        assertThat(dto.getCriticalReadBackValue()).isNull();
        assertThat(dto.getCriticalReadBackRefusedValue()).isNull();
        assertThat(dto.getCriticalReadBackRefusedBy()).isNull();
    }
}
