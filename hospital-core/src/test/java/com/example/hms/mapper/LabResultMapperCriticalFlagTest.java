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

    /** QA D834: a qualitative answer is matched to the test's expected one, never guessed. */
    @Test
    void d834_qualitativeAnswersAgainstTheExpectedResult() {
        assertThat(LabResultMapper.matchesExpectedResult("Négatif", "Négatif")).isTrue();
        assertThat(LabResultMapper.matchesExpectedResult("NEGATIF", "Négatif")).isTrue();
        assertThat(LabResultMapper.matchesExpectedResult("négatif (-)", "Négatif")).isTrue();
        assertThat(LabResultMapper.matchesExpectedResult("  Négatif. ", "Négatif")).isTrue();
        assertThat(LabResultMapper.matchesExpectedResult("Positif", "Négatif")).isFalse();
        assertThat(LabResultMapper.matchesExpectedResult("Douteux", "Négatif")).isFalse();
        assertThat(LabResultMapper.matchesExpectedResult("0", "0")).as("a number is not an answer").isFalse();
        assertThat(LabResultMapper.matchesExpectedResult("Négatif", null)).isFalse();
        assertThat(LabResultMapper.matchesExpectedResult(null, "Négatif")).isFalse();
    }

    /** QA D834: an analyzer's CRITICAL flag is never downgraded by a matching answer. */
    @Test
    void d834_aCriticalFlagWinsOverAMatchingAnswer() {
        com.example.hms.model.LabTestDefinition tdr = new com.example.hms.model.LabTestDefinition();
        tdr.setExpectedResult("Négatif");
        com.example.hms.model.LabOrder order = new com.example.hms.model.LabOrder();
        order.setLabTestDefinition(tdr);
        LabResult flagged = result("Négatif");
        flagged.setLabOrder(order);

        assertThat(mapper.severityFlagOf(flagged)).isEqualTo("NORMAL");
        assertThat(mapper.toResponseDTO(flagged).getExpectedResult()).isEqualTo("Négatif");
        flagged.setAbnormalFlag(AbnormalFlag.CRITICAL);
        assertThat(mapper.severityFlagOf(flagged)).isEqualTo(LabResultMapper.FLAG_CRITICAL);
    }
}
