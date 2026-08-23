package com.example.hms.utility;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link Hl7v2MessageBuilder#parseAdtMessage}
 * and the OBR placer/filler extension on {@code parseOruR01}. These
 * paths are exercised indirectly via {@code Hl7MessageDispatcherTest},
 * but the dispatcher only checks ACK shape — this class covers the
 * field-level parsing contract that downstream services rely on.
 */
class Hl7v2MessageBuilderAdtParserTest {

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    @Nested
    @DisplayName("parseAdtMessage")
    class AdtParser {

        @Test
        @DisplayName("returns null on null / blank input")
        void nullOrBlank() {
            assertThat(builder.parseAdtMessage(null, "A01")).isNull();
            assertThat(builder.parseAdtMessage("", "A01")).isNull();
            assertThat(builder.parseAdtMessage("   ", "A01")).isNull();
        }

        @Test
        @DisplayName("returns null when PID segment is missing")
        void missingPid() {
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A01|CTRL-1|P|2.5\r"
                       + "PV1|1|I|WARD-A\r";
            assertThat(builder.parseAdtMessage(adt, "A01")).isNull();
        }

        @Test
        @DisplayName("returns null when PID-3 MRN is blank")
        void blankMrn() {
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A08|CTRL-2|P|2.5\r"
                       + "PID|1||||DOE^JANE\r";
            assertThat(builder.parseAdtMessage(adt, "A08")).isNull();
        }

        @Test
        @DisplayName("happy path — full PID + PV1 fields are populated")
        void fullParse() {
            // PV1 field counts: f[1]=Set ID, f[2]=patient class, f[3]=location,
            // f[19]=visit number, f[44]=admit datetime, f[45]=discharge datetime.
            // Build the string with explicit pipe counts so the indexes are
            // unambiguous rather than relying on hand-counted padding.
            String pv1 = "PV1|1|I|WARD-A^ROOM-12^BED-3"
                       + "|".repeat(16) + "VISIT-99"      // → f[19]
                       + "|".repeat(25) + "20260428083000"  // → f[44] admit (yyyyMMddHHmmss)
                       + "|" + "20260430090000"             // → f[45] discharge
                       + "\r";
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A01|CTRL-3|P|2.5\r"
                       + "PID|1||MRN-001^^^Authority-1||DOE^JANE^Q||19850101|F|||1 Main St^^Ouagadougou^Centre^01000^BF\r"
                       + pv1;

            ParsedAdtMessage parsed = builder.parseAdtMessage(adt, "A01");

            assertThat(parsed).isNotNull();
            assertThat(parsed.triggerEvent()).isEqualTo("A01");
            assertThat(parsed.mrn()).isEqualTo("MRN-001");
            assertThat(parsed.mrnAssigningAuthority()).isEqualTo("Authority-1");
            assertThat(parsed.lastName()).isEqualTo("DOE");
            assertThat(parsed.firstName()).isEqualTo("JANE");
            assertThat(parsed.middleName()).isEqualTo("Q");
            assertThat(parsed.dateOfBirth()).isEqualTo(LocalDate.of(1985, 1, 1));
            assertThat(parsed.sex()).isEqualTo("F");
            assertThat(parsed.addressLine1()).isEqualTo("1 Main St");
            assertThat(parsed.city()).isEqualTo("Ouagadougou");
            assertThat(parsed.state()).isEqualTo("Centre");
            assertThat(parsed.zipCode()).isEqualTo("01000");
            assertThat(parsed.country()).isEqualTo("BF");
            assertThat(parsed.patientClass()).isEqualTo("I");
            assertThat(parsed.assignedLocation()).startsWith("WARD-A");
            assertThat(parsed.visitNumber()).isEqualTo("VISIT-99");
            assertThat(parsed.admitDateTime()).isEqualTo(LocalDateTime.of(2026, 4, 28, 8, 30, 0));
            assertThat(parsed.dischargeDateTime()).isEqualTo(LocalDateTime.of(2026, 4, 30, 9, 0, 0));
        }

        @Test
        @DisplayName("PV1 absent — PID fields parsed, PV1-derived fields empty / null")
        void pidOnlyParseSucceeds() {
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A08|CTRL-4|P|2.5\r"
                       + "PID|1||MRN-002||SMITH^JOHN||19720315|M\r";

            ParsedAdtMessage parsed = builder.parseAdtMessage(adt, "A08");

            assertThat(parsed).isNotNull();
            assertThat(parsed.mrn()).isEqualTo("MRN-002");
            assertThat(parsed.lastName()).isEqualTo("SMITH");
            assertThat(parsed.firstName()).isEqualTo("JOHN");
            assertThat(parsed.dateOfBirth()).isEqualTo(LocalDate.of(1972, 3, 15));
            assertThat(parsed.patientClass()).isEmpty();
            assertThat(parsed.assignedLocation()).isEmpty();
            assertThat(parsed.visitNumber()).isEmpty();
            assertThat(parsed.admitDateTime()).isNull();
            assertThat(parsed.dischargeDateTime()).isNull();
        }

        @Test
        @DisplayName("invalid date in PID-7 yields null dateOfBirth without failing the parse")
        void invalidDateOfBirthDegradesGracefully() {
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A04|CTRL-5|P|2.5\r"
                       + "PID|1||MRN-003||DOE^JANE||abcdefgh|F\r";

            ParsedAdtMessage parsed = builder.parseAdtMessage(adt, "A04");

            assertThat(parsed).isNotNull();
            assertThat(parsed.mrn()).isEqualTo("MRN-003");
            assertThat(parsed.dateOfBirth()).isNull();
        }

        @Test
        @DisplayName("PID-3 with multiple identifiers — first one wins")
        void firstIdentifierWins() {
            String adt = "MSH|^~\\&|REG|HOSP1|HMS|HOSP1|20260428||ADT^A08|CTRL-6|P|2.5\r"
                       + "PID|1||PRIMARY-MRN^^^AuthA~SECONDARY-ID^^^AuthB||DOE^JANE\r";

            ParsedAdtMessage parsed = builder.parseAdtMessage(adt, "A08");

            assertThat(parsed).isNotNull();
            assertThat(parsed.mrn()).isEqualTo("PRIMARY-MRN");
            assertThat(parsed.mrnAssigningAuthority()).isEqualTo("AuthA");
        }
    }

    @Nested
    @DisplayName("parseOruR01 — every OBX, grouped under its OBR")
    class OruParser {

        @Test
        @DisplayName("captures OBR-2 placer and OBR-3 filler order numbers")
        void capturesPlacerAndFiller() {
            String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-1|P|2.5.1\r"
                       + "PID|1||MRN-1\r"
                       + "OBR|1|ACC-PLACER||GLU^Glucose|||20260428073000\r"
                       + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|3.9-6.1||N|||F|||20260428073000\r";

            List<ParsedObservation> observations = builder.parseOruR01(oru);

            assertThat(observations).hasSize(1);
            ParsedObservation obs = observations.get(0);
            assertThat(obs.placerOrderNumber()).isEqualTo("ACC-PLACER");
            assertThat(obs.fillerOrderNumber()).isEmpty();
            assertThat(obs.setId()).isEqualTo("1");
            assertThat(obs.testCode()).isEqualTo("GLU");
            assertThat(obs.resultValue()).isEqualTo("5.6");
            assertThat(obs.resultUnit()).isEqualTo("mmol/L");
            assertThat(obs.referenceRange()).isEqualTo("3.9-6.1");
        }

        @Test
        @DisplayName("captures both OBR-2 and OBR-3 when sender echoes them")
        void capturesBothOrderNumbers() {
            String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-2|P|2.5.1\r"
                       + "PID|1||MRN-1\r"
                       + "OBR|1|ACC-PLACER|FILLER-789|GLU^Glucose|||20260428073000\r"
                       + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

            List<ParsedObservation> observations = builder.parseOruR01(oru);

            assertThat(observations).hasSize(1);
            assertThat(observations.get(0).placerOrderNumber()).isEqualTo("ACC-PLACER");
            assertThat(observations.get(0).fillerOrderNumber()).isEqualTo("FILLER-789");
        }

        @Test
        @DisplayName("returns EVERY OBX of a panel, in transmission order")
        void returnsEveryObx() {
            String oru = "MSH|^~\\&|SYSMEX|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-CBC|P|2.5.1\r"
                       + "PID|1||MRN-1\r"
                       + "OBR|1|ACC-CBC||CBC^Complete Blood Count|||20260428073000\r"
                       + "OBX|1|NM|WBC^Leukocytes||6.2|10*9/L|4.0-10.0|N|||F\r"
                       + "OBX|2|NM|HGB^Hemoglobin||6.6|g/dL|13.0-17.0|LL|||F\r"
                       + "OBX|3|NM|PLT^Platelets||150|10*9/L|150-400|N|||F\r";

            List<ParsedObservation> observations = builder.parseOruR01(oru);

            assertThat(observations).hasSize(3);
            assertThat(observations).extracting(ParsedObservation::setId)
                .containsExactly("1", "2", "3");
            assertThat(observations).extracting(ParsedObservation::testCode)
                .containsExactly("WBC", "HGB", "PLT");
            assertThat(observations).extracting(ParsedObservation::abnormalFlag)
                .containsExactly("N", "LL", "N");
            assertThat(observations).extracting(ParsedObservation::referenceRange)
                .containsExactly("4.0-10.0", "13.0-17.0", "150-400");
            // All three belong to the single OBR group.
            assertThat(observations).extracting(ParsedObservation::placerOrderNumber)
                .containsOnly("ACC-CBC");
        }

        @Test
        @DisplayName("multi-OBR message groups each OBX under its own OBR's order numbers")
        void groupsObxUnderItsOwnObr() {
            String oru = "MSH|^~\\&|COBAS|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-2ORD|P|2.5.1\r"
                       + "PID|1||MRN-1\r"
                       + "OBR|1|ACC-A||GLU^Glucose\r"
                       + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r"
                       + "OBR|2|ACC-B||UREA^Urea\r"
                       + "OBX|1|NM|UREA^Urea||3.1|mmol/L|||N\r";

            List<ParsedObservation> observations = builder.parseOruR01(oru);

            assertThat(observations).hasSize(2);
            assertThat(observations.get(0).placerOrderNumber()).isEqualTo("ACC-A");
            assertThat(observations.get(1).placerOrderNumber()).isEqualTo("ACC-B");
        }

        @Test
        @DisplayName("returns null on blank input")
        void nullOnBlank() {
            assertThat(builder.parseOruR01(null)).isNull();
            assertThat(builder.parseOruR01("")).isNull();
            assertThat(builder.parseOruR01("   ")).isNull();
        }

        @Test
        @DisplayName("returns an empty list when there is no OBX segment")
        void emptyWhenNoObx() {
            String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428||ORU^R01|MSG-3|P|2.5\r"
                       + "PID|1||MRN-1\r"
                       + "OBR|1|ACC-PLACER||GLU^Glucose\r";
            assertThat(builder.parseOruR01(oru)).isEmpty();
        }
    }
}
