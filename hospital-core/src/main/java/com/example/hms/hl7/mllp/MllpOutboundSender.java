package com.example.hms.hl7.mllp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Transmits one HL7 message over MLLP and reads the ACK (P2 #17).
 *
 * <p>The mirror of {@link MllpTcpServer}. Reuses {@link MllpFrameCodec} for
 * framing so the two halves cannot drift apart on the wire format.
 *
 * <p>One connection per message rather than a pooled long-lived socket: lab
 * interfaces are low-volume and frequently behind equipment that drops idle
 * connections without notice, so a fresh connect is both simpler and more
 * reliable than detecting a half-open socket after the fact.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MllpOutboundSender {

    private final MllpOutboundProperties properties;

    /**
     * @return the ACK the receiver returned
     * @throws IOException when the message could not be delivered or no ACK came
     *         back — the caller records it and retries
     */
    public String send(String message) throws IOException {
        Charset charset = Charset.forName(properties.getCharset());

        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(properties.getHost(), properties.getPort()),
                properties.getConnectTimeoutMs());
            socket.setSoTimeout(properties.getReadTimeoutMs());

            OutputStream out = socket.getOutputStream();
            MllpFrameCodec.writeFrame(out, message, charset);

            InputStream in = socket.getInputStream();
            byte[] ackFrame = MllpFrameCodec.readFrame(in, properties.getMaxFrameBytes());
            if (ackFrame == null || ackFrame.length == 0) {
                throw new IOException("Receiver closed the connection without acknowledging");
            }
            return new String(ackFrame, charset);
        }
    }

    /**
     * Whether the ACK says the receiver accepted the message.
     *
     * <p>MSA-1 carries the acknowledgement code: AA (accept) and CA (commit
     * accept) are success; AE/AR/CE/CR are the receiver telling us it did NOT
     * take the message. Treating any returned ACK as success is the classic way
     * an interface reports green while dropping every message.
     */
    public boolean isPositiveAck(String ack) {
        if (ack == null || ack.isBlank()) {
            return false;
        }
        for (String segment : ack.split("\r|\n")) {
            if (!segment.startsWith("MSA")) {
                continue;
            }
            String[] fields = segment.split("\\|");
            if (fields.length < 2) {
                return false;
            }
            String code = fields[1].trim().toUpperCase(java.util.Locale.ROOT);
            return "AA".equals(code) || "CA".equals(code);
        }
        // No MSA segment at all: this is not an acknowledgement, whatever it is.
        return false;
    }
}
