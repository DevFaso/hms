package com.example.hms.mapper;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of #903. QA D291: every lab screen shows the order's readable number
 * (LAB-000123), never its UUID. QA D286: a positive qualitative answer that no
 * range grades reads ABNORMAL, not "Non evalue" - including a row stored
 * before the write-time rule existed (flag still null).
 */
class LabOrderCodeAndQualitativeFlagTest {

    private final LabResultMapper resultMapper = new LabResultMapper();

    private static LabOrder order(String number) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setOrderNumber(number);
        return order;
    }

    private static LabResult result(String value, AbnormalFlag flag) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order("LAB-000007"));
        result.setResultValue(value);
        result.setAbnormalFlag(flag);
        return result;
    }

    @Test
    void theOrderCodeIsTheReadableNumber() {
        assertThat(new LabOrderMapper().toLabOrderResponseDTO(order("LAB-000123")).getLabOrderCode())
            .isEqualTo("LAB-000123");
        assertThat(resultMapper.toResponseDTO(result("4.2", null)).getLabOrderCode()).isEqualTo("LAB-000007");
    }

    @Test
    void aPositiveAnswerWithNoRangeIsAbnormal() {
        assertThat(resultMapper.toResponseDTO(result("Positif", AbnormalFlag.ABNORMAL)).getSeverityFlag())
            .isEqualTo(LabResultMapper.FLAG_ABNORMAL);
        assertThat(resultMapper.severityFlagOf(result("Positif", AbnormalFlag.ABNORMAL)))
            .isEqualTo(LabResultMapper.FLAG_ABNORMAL);
        // Stored before the rule: no flag, derived from the value at read time.
        assertThat(resultMapper.severityFlagOf(result("Positif", null))).isEqualTo(LabResultMapper.FLAG_ABNORMAL);
    }

    @Test
    void aNegativeOrAnExplicitNormalStaysUngraded() {
        assertThat(resultMapper.severityFlagOf(result("Negatif", null))).isEqualTo(LabResultMapper.FLAG_UNSPECIFIED);
        assertThat(resultMapper.severityFlagOf(result("Positif", AbnormalFlag.NORMAL)))
            .isEqualTo(LabResultMapper.FLAG_UNSPECIFIED);
    }
}
