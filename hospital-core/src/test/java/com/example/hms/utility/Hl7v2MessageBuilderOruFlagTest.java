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

    /** OBX-11 (observation result status) is parsed; a short OBX yields an empty status, not null. */
    @ParameterizedTest
    @CsvSource({
        "'OBX|1|NM|GLU^Glucose||5.6|mmol/L|3.9-6.1|N|||F|||20260428', F",
        "'OBX|1|NM|GLU^Glucose||5.6|mmol/L|3.9-6.1|N|||P|||20260428', P",
        "'OBX|1|NM|GLU^Glucose||5.6|mmol/L|3.9-6.1|N||| C |||20260428', C",
        "'OBX|1|NM|GLU^Glucose||5.6|mmol/L|3.9-6.1|N', ''"
    })
    void inboundObx11IsParsed(String obx, String expectedStatus) {
        String oru = "MSH|^~\\&|APP|FAC|HMS|HOSP|20260428||ORU^R01|MSG-1|P|2.5\r"
            + "PID|1||p\r"
            + "OBR|1|ACC-1||GLU^Glucose|||20260428\r"
            + obx + "\r";

        assertThat(builder.parseOruR01(oru)).singleElement()
            .extracting(Hl7v2MessageBuilder.ParsedObservation::resultStatus)
            .isEqualTo(expectedStatus);
    }
}
