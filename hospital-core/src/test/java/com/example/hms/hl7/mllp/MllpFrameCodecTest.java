package com.example.hms.hl7.mllp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MllpFrameCodecTest {

    @Test
    void writesAndReadsRoundTrip() throws Exception {
        String hl7 = "MSH|^~\\&|S|F|R|RF|20260428||ORU^R01|123|P|2.5\rPID|1||X\r";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MllpFrameCodec.writeFrame(out, hl7, StandardCharsets.UTF_8);

        byte[] wire = out.toByteArray();
        assertThat(wire[0]).isEqualTo(MllpFrameCodec.START_BLOCK);
        assertThat(wire[wire.length - 2]).isEqualTo(MllpFrameCodec.END_BLOCK);
        assertThat(wire[wire.length - 1]).isEqualTo(MllpFrameCodec.CARRIAGE_RETURN);

        byte[] body = MllpFrameCodec.readFrame(new ByteArrayInputStream(wire), 4096);
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(hl7);
    }

    @Test
    void readFrameSkipsLeadingJunkBeforeStartBlock() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("garbage_before".getBytes(StandardCharsets.US_ASCII));
        MllpFrameCodec.writeFrame(out, "MSH|^~\\&|", StandardCharsets.UTF_8);

        byte[] body = MllpFrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray()), 4096);
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("MSH|^~\\&|");
    }

    @Test
    void readFrameReturnsEmptyArrayOnGracefulEofBeforeAnyStartBlock() throws Exception {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[0]);
        byte[] frame = MllpFrameCodec.readFrame(stream, 4096);
        assertThat(frame).isEmpty();
    }

    @Test
    void readFrameRejectsBodyExceedingLimit() {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{
            MllpFrameCodec.START_BLOCK, 'A', 'B', 'C', 'D', 'E'
        });
        assertThatThrownBy(() -> MllpFrameCodec.readFrame(stream, 3))
            .isInstanceOf(MllpProtocolException.class)
            .hasMessageContaining("exceeds limit");
    }

    @Test
    void readFrameRejectsTruncatedTail() {
        ByteArrayInputStream stream = new ByteArrayInputStream(
            new byte[]{ MllpFrameCodec.START_BLOCK, 'M', 'S', 'H' });
        assertThatThrownBy(() -> MllpFrameCodec.readFrame(stream, 4096))
            .isInstanceOf(EOFException.class);
    }

    @Test
    void readFrameRejectsMissingCarriageReturnAfterEndBlock() {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{
            MllpFrameCodec.START_BLOCK, 'M', 'S', 'H',
            MllpFrameCodec.END_BLOCK, 'X'
        });
        assertThatThrownBy(() -> MllpFrameCodec.readFrame(stream, 4096))
            .isInstanceOf(MllpProtocolException.class)
            .hasMessageContaining("Expected <CR>");
    }
}
