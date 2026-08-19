package com.example.hms.hl7.mllp;

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
}
