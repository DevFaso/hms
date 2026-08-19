package com.example.hms.hl7.mllp;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
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
 * ORU^R01 over TCP, and asserts a framed ACK comes back.
 *
 * <p>Since P1 #2b introduced the {@code MllpAllowedSenderService}
 * allowlist gate, an unseeded inbound message gets {@code AR — Sender
 * not authorised} rather than {@code AA}. This smoke test verifies
 * three things end-to-end: (1) the listener accepts framed bytes,
 * (2) the dispatcher consults the allowlist, and (3) a framed ACK is
 * returned. A full happy-path integration test (allowlist + LabOrder
 * + LabSpecimen seeded) is tracked as P1 #2b follow-up work.
 */
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@TestPropertySource(properties = {
    "app.hl7.mllp.enabled=true",
    "app.hl7.mllp.port=0",
    "app.hl7.mllp.bindAddress=127.0.0.1",
    // PR #287 CI fix — pin THIS test to its own H2 in-memory DB so a sibling
    // @SpringBootTest's create-drop shutdown can not drop the platform schema
    // out from under our mllp-worker thread mid-query. The earlier attempt to
    // do this via @DynamicPropertySource on TestPostgresConfig (commit
    // c08a745d) did not take effect — Spring's TestContext framework only
    // discovers @DynamicPropertySource on the test class itself or its
    // enclosing classes, NOT on @TestConfiguration classes brought in via
    // @Import. ${random.uuid} is expanded by Spring Boot's
    // RandomValuePropertySource at property resolution time, so each context
    // load picks a fresh URL. Width: 32 hex chars + dashes + literal prefix
    // is well under H2's identifier limit. PostgreSQL mode + the same H2
    // dialect knobs the rest of the test profile uses.
    "spring.datasource.url=jdbc:h2:mem:mllpit_${random.uuid}"
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
// Drop the Spring context as soon as this class is done so the unique H2 DB
// above is reclaimed cleanly; nothing downstream needs the test's state.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MllpTcpServerIT {

    @Autowired
    private MllpTcpServer server;

    @Test
    @DisplayName("client → MLLP listener: framed ORU^R01 exchanged for framed AR ACK (allowlist denies unseeded sender)")
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
                // No allowlist row seeded — the dispatcher must reject
                // the sender before any domain work is attempted.
                assertThat(ack).contains("MSA|AR|MSG-IT");
                assertThat(ack).contains("Sender not authorised");
                assertThat(ack).startsWith("MSH|^~\\&|HMS|HOSP1|MINDRAY|LAB1|");
            }
        }
    }
}
