package com.example.hms.utility;

import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBX-6 is stored as the unit the result is graded in, so the parser keeps
 * only its identifier (first component) and decodes the HL7 escapes in it.
 * Splitting at compare time instead collapsed exponent units: 10^9/L,
 * 10^12/L and 10^3/uL all read "10".
 */
class Hl7v2MessageBuilderObxUnitTest {

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    private ParsedObservation parseWithUnit(String obx6) {
        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-1|P|2.5.1\r"
            + "PID|1||MRN-1\r"
            + "OBR|1|ACC-1||GLU^Glucose|||20260428073000\r"
            + "OBX|1|NM|GLU^Glucose||5.6|" + obx6 + "|3.9-6.1||N|||F|||20260428073000\r";
        List<ParsedObservation> observations = builder.parseOruR01(oru);
        assertThat(observations).hasSize(1);
        return observations.get(0);
    }

    @Test
    void aCodedUnitIsStoredAsItsIdentifier() {
        assertThat(parseWithUnit("mmol/L^millimole per liter^UCUM").resultUnit()).isEqualTo("mmol/L");
    }

    @Test
    void anEscapedCaretInsideTheUnitSurvives() {
        assertThat(parseWithUnit("10\\S\\9/L^billion per liter^UCUM").resultUnit()).isEqualTo("10^9/L");
        assertThat(parseWithUnit("10\\S\\12/L").resultUnit()).isEqualTo("10^12/L");
    }

    @Test
    void aPlainUnitAndAnEmptyIdentifierAreKept() {
        assertThat(parseWithUnit("g/dL").resultUnit()).isEqualTo("g/dL");
        assertThat(parseWithUnit("^^UCUM").resultUnit()).isEmpty();
        assertThat(parseWithUnit("").resultUnit()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "a\\F\\b; a|b",
        "a\\S\\b; a^b",
        "a\\R\\b; a~b",
        "a\\T\\b; a&b",
        "a\\E\\b; a\\b",
        "a\\X\\b; a\\X\\b",
        "trailing\\; trailing\\"
    })
    void decodesTheDelimiterEscapesAndKeepsAnythingElse(String encoded, String decoded) {
        assertThat(Hl7v2MessageBuilder.decodeEscapes(encoded)).isEqualTo(decoded);
    }
}
