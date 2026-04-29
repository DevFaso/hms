package com.example.hms.hl7.mllp;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7AckBuilderTest {

    @Test
    void buildsAaAckMirroringSenderAndControlId() {
        Hl7MessageHeader inbound = new Hl7MessageHeader(
            "|", "^~\\&", "MINDRAY", "LAB1", "HMS", "HOSP1",
            "20260428073000", "ORU^R01", "MSG-42", "P", "2.5.1"
        );

        String ack = Hl7AckBuilder.buildAck(inbound, Hl7AckBuilder.AckCode.AA, null,
            LocalDateTime.of(2026, 4, 28, 7, 30, 0), "ACK-1");

        assertThat(ack)
            .startsWith("MSH|^~\\&|HMS|HOSP1|MINDRAY|LAB1|20260428073000||ACK|ACK-1|P|2.5.1\r")
            .endsWith("MSA|AA|MSG-42\r");
    }

    @Test
    void buildsArAckWithReason() {
        Hl7MessageHeader inbound = new Hl7MessageHeader(
            "|", "^~\\&", "X", "Y", "HMS", "HOSP",
            "20260428073000", "ZZZ^Z99", "C-1", "P", "2.5"
        );

        String ack = Hl7AckBuilder.buildAck(inbound, Hl7AckBuilder.AckCode.AR,
            "Unsupported message type ZZZ^Z99", LocalDateTime.now(), "C-1-ACK");

        assertThat(ack).contains("MSA|AR|C-1|Unsupported message type ZZZ^Z99");
    }
}
