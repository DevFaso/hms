package com.example.hms.hl7.mllp;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the listener on an ephemeral port (port=0), sends a framed
 * ORU^R01 over TCP, and asserts a framed AA ACK comes back.
 */
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = {
    "app.hl7.mllp.enabled=true",
    "app.hl7.mllp.port=0",
    "app.hl7.mllp.bindAddress=127.0.0.1"
})
class MllpTcpServerIT {

    @Autowired
    private MllpTcpServer server;

    @Test
    @DisplayName("client → MLLP listener: framed ORU^R01 exchanged for framed AA ACK")
    void roundTripsAnOruMessage() throws Exception {
        int port = server.getBoundPort();
        assertThat(port).isPositive();

        String oru = "MSH|^~\\&|MINDRAY|LAB1|HMS|HOSP1|20260428073000||ORU^R01|MSG-IT|P|2.5.1\r"
                   + "PID|1||p-uuid\r"
                   + "OBR|1||ord-IT|GLU^Glucose|||20260428073000\r"
                   + "OBX|1|NM|GLU^Glucose||5.6|mmol/L|||N\r";

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            client.setSoTimeout(5_000);
            try (
                OutputStream out = new BufferedOutputStream(client.getOutputStream());
                InputStream in = new BufferedInputStream(client.getInputStream())
            ) {
                MllpFrameCodec.writeFrame(out, oru, StandardCharsets.UTF_8);

                byte[] ackBytes = MllpFrameCodec.readFrame(in, 64 * 1024);
                assertThat(ackBytes).isNotNull();
                String ack = new String(ackBytes, StandardCharsets.UTF_8);
                assertThat(ack).contains("MSA|AA|MSG-IT");
                assertThat(ack).startsWith("MSH|^~\\&|HMS|HOSP1|MINDRAY|LAB1|");
            }
        }
    }
}
