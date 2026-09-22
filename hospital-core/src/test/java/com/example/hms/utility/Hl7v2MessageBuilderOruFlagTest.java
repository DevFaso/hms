package com.example.hms.utility;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7v2MessageBuilderOruFlagTest {

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    /** B18 — what came in as L/H goes back out as L/H, not flattened to A. */
    @ParameterizedTest
    @CsvSource({
        "NORMAL, N",
        "ABNORMAL, A",
        "ABNORMAL_LOW, L",
        "ABNORMAL_HIGH, H",
        "CRITICAL, HH"
    })
    void outboundObxCarriesTheDirection(AbnormalFlag flag, String obx8) {
        LabOrder order = new LabOrder();
        LabResult result = LabResult.builder()
            .labOrder(order)
            .resultValue("5.7")
            .resultUnit("mmol/L")
            .resultDate(LocalDateTime.of(2026, 5, 15, 10, 12))
            .abnormalFlag(flag)
            .build();

        String oru = builder.buildOruR01(result);

        assertThat(oru).contains("||5.7|mmol/L||||" + obx8 + "|||F|||");
    }
}
