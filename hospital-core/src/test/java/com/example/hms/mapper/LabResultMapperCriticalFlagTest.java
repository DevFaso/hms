package com.example.hms.mapper;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #901: CRITICAL is worked out from the result itself (value, range,
 * analyzer flag), never from the notification stamp. The stamp records a page
 * about a value that may since have been amended, so a corrected typo
 * (79 -> 4.2) was labelled CRITICAL for good.
 */
class LabResultMapperCriticalFlagTest {

    private final LabResultMapper mapper = new LabResultMapper();

    private static LabResult result(String value) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setResultValue(value);
        return result;
    }

    @Test
    void aNotificationStampAloneDoesNotMakeAResultCritical() {
        LabResult corrected = result("4.2");
        corrected.setCriticalNotifiedAt(LocalDateTime.now().minusMinutes(5));

        assertThat(mapper.severityFlagOf(corrected)).isNotEqualTo(LabResultMapper.FLAG_CRITICAL);
        assertThat(mapper.toResponseDTO(corrected).getSeverityFlag()).isNotEqualTo(LabResultMapper.FLAG_CRITICAL);
    }

    @Test
    void anAnalyzersCriticalFlagIsStillCritical() {
        LabResult flagged = result("4.2");
        flagged.setAbnormalFlag(AbnormalFlag.CRITICAL);

        assertThat(mapper.severityFlagOf(flagged)).isEqualTo(LabResultMapper.FLAG_CRITICAL);
    }
}
