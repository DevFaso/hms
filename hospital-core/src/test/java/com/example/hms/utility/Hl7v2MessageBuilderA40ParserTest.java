package com.example.hms.utility;

import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ADT^A40} parsing (Tier 2 item 41).
 *
 * <p>The direction is the whole risk here. In an A40 the PID carries the
 * identifier that SURVIVES and the MRG carries the one being retired. Reading
 * it backwards merges away the wrong patient, and nothing on the receiving
 * side makes that reversible.
 */
class Hl7v2MessageBuilderA40ParserTest {

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    private static final String A40 =
        "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-A40-1|P|2.5\r"
        + "EVN|A40|20260826120000\r"
        + "PID|1||MRN-SURVIVOR^^^HOSP1^MR||Traore^Awa||19900101|F\r"
        + "MRG|MRN-RETIRED^^^HOSP1^MR\r";

    @Test
    void pidIsTheSurvivorAndMrgIsTheOneRetired() {
        ParsedMergeMessage parsed = builder.parseAdtA40(A40);

        assertThat(parsed).isNotNull();
        // If these two ever swap, the software merges away the patient that
        // was supposed to survive.
        assertThat(parsed.survivingMrn()).isEqualTo("MRN-SURVIVOR");
        assertThat(parsed.priorMrn()).isEqualTo("MRN-RETIRED");
    }

    @Test
    void assigningAuthoritiesAreCarriedForBothSides() {
        ParsedMergeMessage parsed = builder.parseAdtA40(A40);

        assertThat(parsed.survivingMrnAssigningAuthority()).isEqualTo("HOSP1");
        assertThat(parsed.priorMrnAssigningAuthority()).isEqualTo("HOSP1");
    }

    @Test
    void aMessageWithNoMrgSegmentIsNotAMerge() {
        // This is the case that would otherwise fall through to the
        // demographic handler and silently apply an update instead.
        String noMrg =
            "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-1|P|2.5\r"
            + "PID|1||MRN-SURVIVOR^^^HOSP1^MR||Traore^Awa||19900101|F\r";

        assertThat(builder.parseAdtA40(noMrg)).isNull();
    }

    @Test
    void aMessageWithNoPidSegmentIsRefused() {
        String noPid =
            "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-1|P|2.5\r"
            + "MRG|MRN-RETIRED^^^HOSP1^MR\r";

        assertThat(builder.parseAdtA40(noPid)).isNull();
    }

    @Test
    void anEmptyIdentifierOnEitherSideIsRefusedRatherThanGuessed() {
        String blankMrg =
            "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-1|P|2.5\r"
            + "PID|1||MRN-SURVIVOR^^^HOSP1^MR||Traore^Awa||19900101|F\r"
            + "MRG|\r";
        assertThat(builder.parseAdtA40(blankMrg)).isNull();

        String blankPid =
            "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-1|P|2.5\r"
            + "PID|1||\r"
            + "MRG|MRN-RETIRED^^^HOSP1^MR\r";
        assertThat(builder.parseAdtA40(blankPid)).isNull();
    }

    @Test
    void nullOrBlankInputIsNull() {
        assertThat(builder.parseAdtA40(null)).isNull();
        assertThat(builder.parseAdtA40("   ")).isNull();
    }

    @Test
    void lineEndingsAndBareIdentifiersAreTolerated() {
        // Senders vary: \n instead of \r, and an MRN with no components.
        String lenient =
            "MSH|^~\\&|LIS|HOSP1|HMS|HOSP1|20260826120000||ADT^A40|MSG-1|P|2.5\n"
            + "PID|1||MRN-A\n"
            + "MRG|MRN-B\n";

        ParsedMergeMessage parsed = builder.parseAdtA40(lenient);

        assertThat(parsed).isNotNull();
        assertThat(parsed.survivingMrn()).isEqualTo("MRN-A");
        assertThat(parsed.priorMrn()).isEqualTo("MRN-B");
    }
}
