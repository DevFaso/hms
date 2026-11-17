package com.example.hms.hl7.mllp;

import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hl7MessageInspectorTest {

    @Test
    void parsesRoutingFieldsFromMshSegment() {
        String body = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                    + "PID|1||abc-uuid\rOBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        Hl7MessageHeader h = Hl7MessageInspector.parseHeader(body);

        assertThat(h.fieldSeparator()).isEqualTo("|");
        assertThat(h.encodingCharacters()).isEqualTo("^~\\&");
        assertThat(h.sendingApplication()).isEqualTo("MINDRAY");
        assertThat(h.sendingFacility()).isEqualTo("LAB1");
        assertThat(h.receivingApplication()).isEqualTo("HMS");
        assertThat(h.receivingFacility()).isEqualTo("HOSP1");
        assertThat(h.messageType()).isEqualTo("ORU^R01");
        assertThat(h.messageCode()).isEqualTo("ORU");
        assertThat(h.triggerEvent()).isEqualTo("R01");
        assertThat(h.messageControlId()).isEqualTo("MSG-42");
        assertThat(h.versionId()).isEqualTo("2.5.1");
    }

    @Test
    void rejectsBodyNotStartingWithMsh() {
        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader("PID|..."))
            .isInstanceOf(MllpProtocolException.class)
            .hasMessageContaining("MSH");
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader(""))
            .isInstanceOf(MllpProtocolException.class);
    }

    // ── Field widths (Hl7FieldBounds) ────────────────────────────────────

    private static String withHeaderField(String field, String value) {
        String app = "MINDRAY";
        String facility = "LAB1";
        String type = "ORU^R01";
        String controlId = "MSG-42";
        switch (field) {
            case "MSH-3" -> app = value;
            case "MSH-4" -> facility = value;
            case "MSH-9" -> type = value;
            case "MSH-10" -> controlId = value;
            default -> throw new IllegalArgumentException(field);
        }
        return "MSH|^~\\&|" + app + "|" + facility + "|HMS|HOSP1|20260428073000||"
            + type + "|" + controlId + "|P|2.5.1\r"
            + "PID|1||p\r";
    }

    private static String read(Hl7MessageHeader header, String field) {
        return switch (field) {
            case "MSH-3" -> header.sendingApplication();
            case "MSH-4" -> header.sendingFacility();
            case "MSH-9" -> header.messageType();
            case "MSH-10" -> header.messageControlId();
            default -> throw new IllegalArgumentException(field);
        };
    }

    @ParameterizedTest(name = "{0} at exactly {1} characters is read verbatim")
    @CsvSource({"MSH-3, 180", "MSH-4, 180", "MSH-10, 255"})
    void aHeaderFieldAtItsColumnWidthIsReadVerbatim(String field, int max) {
        String value = "Q".repeat(max);

        Hl7MessageHeader header = Hl7MessageInspector.parseHeader(withHeaderField(field, value));

        // Verbatim, not cut: every one of these is matched or keyed on.
        assertThat(read(header, field)).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0} one character over {1} is an invalid MSH")
    @CsvSource({"MSH-3, 180", "MSH-4, 180", "MSH-10, 255"})
    void aHeaderFieldOverItsColumnWidthIsAnInvalidMsh(String field, int max) {
        String body = withHeaderField(field, "Q".repeat(max + 1));

        // hasMessage, not a substring match: the message names the field and
        // the limit and nothing else. It is echoed into the AR and the
        // dead-letter row, and the value is the over-width thing refused.
        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader(body))
            .isInstanceOf(MllpFieldWidthException.class)
            .hasMessage(field + " exceeds " + max + " characters");
    }

    @ParameterizedTest(name = "an MSH-10 of {0} characters is kept whole")
    @ValueSource(ints = {21, 40, 64, 254})
    void anMsh10LongerThanHl7sNominalTwentyIsKeptWhole(int length) {
        // HL7 v2.5 says 20; senders do not. A 20-character cap truncated
        // these in the dead-letter reason, and a cap that tight at parse
        // would refuse messages that store and replay-match correctly.
        String controlId = "C".repeat(length);

        assertThat(Hl7MessageInspector.parseHeader(withHeaderField("MSH-10", controlId))
                .messageControlId())
            .isEqualTo(controlId);
    }

    @Test
    void twoControlIdsThatShareTwentyCharactersStayDistinct() {
        // The collision a truncating cap creates: both of these become
        // "20260428-ANALYZER-01" and read as a replay of each other.
        String first = "20260428-ANALYZER-01-000001";
        String second = "20260428-ANALYZER-01-000002";

        assertThat(Hl7MessageInspector.parseHeader(withHeaderField("MSH-10", first))
                .messageControlId())
            .isEqualTo(first)
            .isNotEqualTo(Hl7MessageInspector.parseHeader(withHeaderField("MSH-10", second))
                .messageControlId());
    }

    @Test
    void msh9IsNotBoundedItIsNotAnIdentifier() {
        // A long message-structure component must not refuse the HTTP ORU
        // ingest, which never reads MSH-9. The recorder clamps the one column
        // it reaches.
        String type = "ORU^R01^" + "S".repeat(100);

        assertThat(Hl7MessageInspector.parseHeader(withHeaderField("MSH-9", type)).messageType())
            .isEqualTo(type);
    }

    @Test
    void anUnreadableBodyIsNotAWidthRefusal() {
        // The HTTP ingest logs only a width refusal at WARN; an empty or
        // non-MSH body must stay a plain protocol exception.
        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader("PID|1"))
            .isInstanceOf(MllpProtocolException.class)
            .isNotInstanceOf(MllpFieldWidthException.class);
    }

    @Test
    void aRefusedSenderFieldKeepsTheControlIdToAnswerOn() {
        // The refusal must echo MSH-10 in MSA-2 or the sender cannot match it
        // and resends. The refused field itself is replaced, never carried.
        String body = withHeaderField("MSH-4", "F".repeat(181));

        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader(body))
            .isInstanceOfSatisfying(MllpFieldWidthException.class, refused -> {
                assertThat(refused.replyHeader()).isNotNull();
                assertThat(refused.replyHeader().messageControlId()).isEqualTo("MSG-42");
                assertThat(refused.replyHeader().sendingApplication()).isEqualTo("MINDRAY");
                assertThat(refused.replyHeader().sendingFacility()).isEqualTo("?");
            });
    }

    @Test
    void anOverWidthControlIdLeavesNothingToEcho() {
        String body = withHeaderField("MSH-10", "C".repeat(256));

        assertThatThrownBy(() -> Hl7MessageInspector.parseHeader(body))
            .isInstanceOfSatisfying(MllpFieldWidthException.class,
                refused -> assertThat(refused.replyHeader()).isNull());
    }
}
