package com.example.hms.hl7.mllp;

import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7MessageDispatcherTest {

    private final Hl7MessageDispatcher dispatcher =
        new Hl7MessageDispatcher(new Hl7v2MessageBuilder());

    @Test
    void acksOruR01WithAa() {
        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-42|P|2.5.1\r"
                   + "PID|1||abc-uuid\r"
                   + "OBR|1||ord-1|GLU^Glucose|||20260428073000\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        String ack = dispatcher.dispatch(oru, "10.0.0.42:54321");

        assertThat(ack).contains("ACK|");
        assertThat(ack).contains("MSA|AA|MSG-42");
    }

    @Test
    void acksAdtA01WithAa() {
        String adt = "MSH|^~\\&|REGISTRATION|HOSP1|HMS|HOSP1|20260428073000||ADT^A01|CTRL-9|P|2.5\r"
                   + "PID|1||p-uuid|||DOE^JANE\r";

        String ack = dispatcher.dispatch(adt, "10.0.0.50:1024");
        assertThat(ack).contains("MSA|AA|CTRL-9");
    }

    @Test
    void rejectsUnsupportedMessageTypeWithAr() {
        String unknown = "MSH|^~\\&|X|Y|HMS|HOSP1|20260428073000||ZZZ^Z99|C-1|P|2.5\r";
        String ack = dispatcher.dispatch(unknown, "10.0.0.51:1");
        assertThat(ack).contains("MSA|AR|C-1");
        assertThat(ack).contains("Unsupported message type ZZZ^Z99");
    }

    @Test
    void rejectsMessageWithBadHeader() {
        String bad = "GARBAGE|||";
        String ack = dispatcher.dispatch(bad, "10.0.0.99:1");
        assertThat(ack).contains("MSA|AR|");
        assertThat(ack).contains("Invalid MSH");
    }

    @Test
    void emitsAeWhenOruParsingFails() {
        String malformedOru = "MSH|^~\\&|S|F|HMS|HOSP|20260428||ORU^R01|MSG-9|P|2.5\r"
                            + "PID|1||p\r";   // no OBX
        String ack = dispatcher.dispatch(malformedOru, "10.0.0.10:1");
        assertThat(ack).contains("MSA|AE|MSG-9|Unparseable ORU^R01");
    }
}
