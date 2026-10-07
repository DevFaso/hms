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

        LabOrder order = result.getLabOrder();
        String oru = builder.buildOruR01(result);

        String[] obx = segment(oru, "OBX").split("\\|", -1);
        assertThat(obx).hasSizeGreaterThan(14);
        assertThat(obx[2]).as("a one-line value is ST").isEqualTo("ST");
        assertThat(obx[6]).as("raw OBX-6").isEqualTo("10\\S\\9/L");
        assertThat(obx[3]).as("each CE part escaped on its own")
            .isEqualTo("WBC\\S\\1^White cells \\T\\ count");
        assertThat(builder.parseOruR01(oru)).singleElement().satisfies(parsed -> {
            ParsedObservation p = parsed;
            assertThat(p.resultUnit()).isEqualTo("10^9/L");
            assertThat(p.resultValue()).isEqualTo(AWKWARD);
            assertThat(p.resultStatus()).isEqualTo("F");
            // Every field the builder escapes is decoded on the way back in.
            assertThat(p.testCode()).isEqualTo("WBC^1");
            assertThat(p.placerOrderNumber()).isEqualTo(order.getId().toString());
            assertThat(p.patientId()).isEqualTo(order.getPatient().getId().toString());
        });
    }

    @Test
    void escapedIdentifiersAreDecodedPerComponent() {
        String oru = "MSH|^~\\&|LIS|LAB1|HMS|HOSP1|20261006093000||ORU^R01|M-1|P|2.5.1\r"
            + "PID|1||MRN\\S\\9^^^HOSP\r"
            + "OBR|1|ACC\\F\\1^LIS|FIL\\T\\2^LIS|GLU^Glucose|||20261006093000\r"
            + "OBX|1|NM|GLU\\S\\X^Glucose\\S\\fasting||5.4|mmol/L|||N|||F|||20261006093000\r";

        assertThat(builder.parseOruR01(oru)).singleElement().satisfies(p -> {
            assertThat(p.patientId()).isEqualTo("MRN^9");
            assertThat(p.placerOrderNumber()).as("the accession the lookup uses").isEqualTo("ACC|1");
            assertThat(p.fillerOrderNumber()).isEqualTo("FIL&2");
            assertThat(p.testCode()).isEqualTo("GLU^X");
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
    void literalFormattingCodesAreTextBothWays() {
        // Text is text: a stored "\H\" is three characters and goes out as such.
        String stored = "\\H\\bold\\N\\ \\.sp2\\ \\Zlocal\\ C:\\temp";

        String encoded = Hl7v2MessageBuilder.encodeEscapes(stored);

        assertThat(encoded).isEqualTo(
            "\\E\\H\\E\\bold\\E\\N\\E\\ \\E\\.sp2\\E\\ \\E\\Zlocal\\E\\ C:\\E\\temp");
        assertThat(Hl7v2MessageBuilder.decodeEscapes(encoded)).isEqualTo(stored);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = ';', value = {
        "Gram\\.br\\S\\.br\\end; Gram{LF}S{LF}end",
        "x\\.br\\F\\.br\\; x{LF}F{LF}",
        "a\\X0D0A\\b; a{CR}{LF}b",
        "a\\X0d\\b; a{CR}b",
        "a\\X41\\b; a\\X41\\b",
        "\\H\\T\\N\\; \\H\\T\\N\\",
        "\\.sp2\\x; \\.sp2\\x",
        "trailing\\; trailing\\"
    })
    void inboundEscapesDecodeToPlainText(String received, String expected) {
        String plain = expected.replace("{LF}", "\n").replace("{CR}", "\r");

        assertThat(Hl7v2MessageBuilder.decodeEscapes(received)).isEqualTo(plain);
    }

    @Test
    void aValueCannotInjectASegment() {
        String forged = "a\rOBX|2|ST|X||evil";
        LabResult result = LabResult.builder().labOrder(order()).resultValue(forged).build();

        String oru = builder.buildOruR01(result);

        assertThat(oru.split("\r")).filteredOn(segment -> segment.startsWith("OBX")).hasSize(1);
        assertThat(builder.parseOruR01(oru)).singleElement()
            .satisfies(p -> assertThat(p.resultValue()).isEqualTo(forged));
    }

    @Test
    void aMultiLineNarrativeRoundTrips() {
        String narrative = "Line 1\r\nLine 2\nLine 3\rend";
        LabResult result = LabResult.builder().labOrder(order()).resultValue(narrative).build();

        String oru = builder.buildOruR01(result);

        assertThat(segment(oru, "OBX").split("\\|", -1)[2]).as("line breaks need FT").isEqualTo("FT");
        assertThat(segment(oru, "OBX").split("\\|", -1)[5])
            .isEqualTo("Line 1\\X0D\\\\.br\\Line 2\\.br\\Line 3\\X0D\\end");
        assertThat(builder.parseOruR01(oru)).singleElement()
            .satisfies(p -> assertThat(p.resultValue()).isEqualTo(narrative));
    }

    /** decode(encode(x)) == x, and nothing encoded can break a field or a segment. */
    @Test
    void encodingRoundTripsEveryString() {
        String alphabet = "abXHNZ.0DAbr19+-\\|^&~\r\n ";
        java.util.Random random = new java.util.Random(823);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(24);
            for (int k = 0; k < length; k++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String x = text.toString();
            String encoded = Hl7v2MessageBuilder.encodeEscapes(x);

            assertThat(Hl7v2MessageBuilder.decodeEscapes(encoded)).as("round trip of [%s]", x).isEqualTo(x);
            assertThat(encoded).as("encoded [%s]", x).doesNotContain("|", "^", "&", "~", "\r", "\n");
        }
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
