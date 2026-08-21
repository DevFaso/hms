package com.example.hms.hl7.mllp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MSA-1 parsing (P2 #17).
 *
 * <p>Treating any returned ACK as success is the classic way an interface
 * reports green while dropping every message, so the accept/reject codes are
 * pinned individually.
 */
class MllpOutboundSenderAckTest {

    private final MllpOutboundSender sender = new MllpOutboundSender(new MllpOutboundProperties());

    @Test
    void applicationAcceptIsPositive() {
        assertThat(sender.isPositiveAck("MSH|^~\\&|LAB\rMSA|AA|MSG00001")).isTrue();
    }

    @Test
    void commitAcceptIsPositive() {
        assertThat(sender.isPositiveAck("MSA|CA|MSG00001")).isTrue();
    }

    @Test
    void applicationErrorIsNotPositive() {
        assertThat(sender.isPositiveAck("MSA|AE|MSG00001|Unknown test code")).isFalse();
    }

    @Test
    void applicationRejectIsNotPositive() {
        assertThat(sender.isPositiveAck("MSA|AR|MSG00001")).isFalse();
    }

    @Test
    void aResponseWithNoMsaSegmentIsNotAnAcknowledgement() {
        // Whatever this is, it is not the receiver saying it took the message.
        assertThat(sender.isPositiveAck("MSH|^~\\&|LAB|something else")).isFalse();
    }

    @Test
    void blankAndNullAreNotPositive() {
        assertThat(sender.isPositiveAck(null)).isFalse();
        assertThat(sender.isPositiveAck("   ")).isFalse();
    }
}
