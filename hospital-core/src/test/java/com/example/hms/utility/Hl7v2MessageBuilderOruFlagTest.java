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

    private static String obxOf(String oru) {
        return oru.lines().filter(l -> l.startsWith("OBX")).findFirst().orElseThrow();
    }

    /**
     * B18 — what came in as L/H goes back out as L/H, not flattened to A,
     * and it goes out in OBX-8 where a receiver looks for it. The split
     * index equals the HL7 field number (element 0 is the segment name).
     */
    @ParameterizedTest
    @CsvSource({
        "NORMAL, N",
        "ABNORMAL, A",
        "ABNORMAL_LOW, L",
        "ABNORMAL_HIGH, H",
        "CRITICAL, HH"
    })
    void outboundObxCarriesTheDirectionInObx8(AbnormalFlag flag, String obx8) {
        String oru = builder.buildOruR01(resultFlagged(flag));

        String[] fields = obxOf(oru).split("\\|", -1);
        assertThat(fields[5]).as("OBX-5 value").isEqualTo("5.7");
        assertThat(fields[6]).as("OBX-6 units").isEqualTo("mmol/L");
        assertThat(fields[8]).as("OBX-8 abnormal flags").isEqualTo(obx8);
        assertThat(fields[11]).as("OBX-11 result status").isEqualTo("F");
        assertThat(fields[14]).as("OBX-14 observation date/time").startsWith("20260515");
    }

    /**
     * The alignment that matters is the one a receiver applies, so read
     * the message back with our own inbound parser: before the fix it
     * came back with no flag at all (OBX-8 empty → NORMAL) and no result
     * status, which is what a downstream analyzer or LIS would have seen.
     */
    @ParameterizedTest
    @CsvSource({
        "NORMAL, N",
        "ABNORMAL, A",
        "ABNORMAL_LOW, L",
        "ABNORMAL_HIGH, H",
        "CRITICAL, HH"
    })
    void outboundOruRoundTripsThroughTheInboundParser(AbnormalFlag flag, String obx8) {
        String oru = builder.buildOruR01(resultFlagged(flag));

        assertThat(builder.parseOruR01(oru)).singleElement().satisfies(parsed -> {
            assertThat(parsed.abnormalFlag()).isEqualTo(obx8);
            assertThat(parsed.resultStatus()).isEqualTo("F");
            assertThat(parsed.resultValue()).isEqualTo("5.7");
            assertThat(parsed.resultUnit()).isEqualTo("mmol/L");
        });
    }

    private static LabResult resultFlagged(AbnormalFlag flag) {
        return LabResult.builder()
            .labOrder(new LabOrder())
            .resultValue("5.7")
            .resultUnit("mmol/L")
            .resultDate(LocalDateTime.of(2026, 5, 15, 10, 12))
            .abnormalFlag(flag)
            .build();
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
