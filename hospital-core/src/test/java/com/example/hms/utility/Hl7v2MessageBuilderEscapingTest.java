package com.example.hms.utility;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stored text written into an outbound message is escaped, the exact inverse
 * of what the inbound parser decodes. #819 stores a decoded unit such as
 * 10^9/L; written raw, its caret split OBX-6 and a receiver - our own parser
 * included - read the unit as "10".
 */
class Hl7v2MessageBuilderEscapingTest {

    private static final String AWKWARD = "a|b^c&d~e\\f";

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    private static String segment(String message, String prefix) {
        return message.lines().flatMap(l -> java.util.Arrays.stream(l.split("\r")))
            .filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    private static LabOrder order() {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setTestCode("WBC^1");
        definition.setName("White cells & count");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setLastName("O^Brien");
        patient.setFirstName("Ann|Marie");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(definition);
        order.setPatient(patient);
        return order;
    }

    @Test
    void aStoredUnitAndValueSurviveTheRoundTrip() {
        LabResult result = LabResult.builder()
            .labOrder(order())
            .resultValue(AWKWARD)
            .resultUnit("10^9/L")
            .resultDate(LocalDateTime.of(2026, 10, 6, 9, 30))
            .build();
        result.setReleased(true);

        String oru = builder.buildOruR01(result);

        String[] obx = segment(oru, "OBX").split("\\|", -1);
        assertThat(obx).hasSizeGreaterThan(14);
        assertThat(obx[6]).as("raw OBX-6").isEqualTo("10\\S\\9/L");
        assertThat(obx[3]).as("each CE part escaped on its own")
            .isEqualTo("WBC\\S\\1^White cells \\T\\ count");
        assertThat(builder.parseOruR01(oru)).singleElement().satisfies(parsed -> {
            ParsedObservation p = parsed;
            assertThat(p.resultUnit()).isEqualTo("10^9/L");
            assertThat(p.resultValue()).isEqualTo(AWKWARD);
            assertThat(p.resultStatus()).isEqualTo("F");
        });
    }

    @Test
    void namesAreEscapedPartByPart() {
        LabResult result = LabResult.builder().labOrder(order()).resultValue("5").build();

        String pid = segment(builder.buildOruR01(result), "PID");

        assertThat(pid.split("\\|", -1)[6]).isEqualTo("O\\S\\Brien^Ann\\F\\Marie");
    }

    @Test
    void theOrderMessageEscapesItsStoredTextToo() {
        LabSpecimen specimen = new LabSpecimen();
        specimen.setLabOrder(order());
        specimen.setAccessionNumber("ACC|1");

        String obr = segment(builder.buildOml021(specimen), "OBR");

        String[] fields = obr.split("\\|", -1);
        assertThat(fields[2]).isEqualTo("ACC\\F\\1");
        assertThat(fields[4]).isEqualTo("WBC\\S\\1^White cells \\T\\ count");
    }

    @Test
    void formattingEscapesPassThroughBothWays() {
        // The decoder keeps these as received; re-escaping their backslash
        // would make a receiver print it instead of breaking the line.
        String stored = "line1\\.br\\line2 \\X0D\\ \\H\\bold a \\sp2\\, odd\\one";

        String encoded = Hl7v2MessageBuilder.encodeEscapes(stored);

        assertThat(encoded).isEqualTo(
            "line1\\.br\\line2 \\X0D\\ \\H\\bold a \\E\\sp2\\E\\, odd\\E\\one");
        assertThat(Hl7v2MessageBuilder.decodeEscapes(encoded)).isEqualTo(stored);
    }

    @Test
    void aFormattedValueSurvivesTheRoundTrip() {
        LabResult result = LabResult.builder()
            .labOrder(order())
            .resultValue("Note\\.br\\repeat \\X0D\\| done")
            .build();

        assertThat(builder.parseOruR01(builder.buildOruR01(result))).singleElement()
            .satisfies(p -> assertThat(p.resultValue()).isEqualTo("Note\\.br\\repeat \\X0D\\| done"));
    }

    private ParsedObservation inbound(String obx2, String obx5) {
        String oru = "MSH|^~\\&|LIS|LAB1|HMS|HOSP1|20261006093000||ORU^R01|M-1|P|2.5.1\r"
            + "OBR|1|ACC-1||MAL^Malaria|||20261006093000\r"
            + "OBX|1|" + obx2 + "|MAL^Malaria||" + obx5 + "|||A|||F|||20261006093000\r";
        return builder.parseOruR01(oru).get(0);
    }

    @Test
    void aCodedValueIsStoredAsReceived() {
        // Decoding the whole CWE would turn the escaped caret inside the
        // text component into a fourth component.
        assertThat(inbound("CWE", "POS^Positive \\S\\ see note^L").resultValue())
            .isEqualTo("POS^Positive \\S\\ see note^L");
        assertThat(inbound("CE", "POS^Positive\\F\\^L").resultValue()).isEqualTo("POS^Positive\\F\\^L");
    }

    @Test
    void aTextValueIsDecoded() {
        assertThat(inbound("ST", "a\\S\\b").resultValue()).isEqualTo("a^b");
        assertThat(inbound("tx", "a\\F\\b").resultValue()).isEqualTo("a|b");
    }

    @Test
    void encodingIsTheInverseOfDecoding() {
        String encoded = Hl7v2MessageBuilder.encodeEscapes(AWKWARD);

        assertThat(encoded).isEqualTo("a\\F\\b\\S\\c\\T\\d\\R\\e\\E\\f");
        assertThat(Hl7v2MessageBuilder.decodeEscapes(encoded)).isEqualTo(AWKWARD);
        assertThat(Hl7v2MessageBuilder.encodeEscapes(null)).isEmpty();
    }
}
