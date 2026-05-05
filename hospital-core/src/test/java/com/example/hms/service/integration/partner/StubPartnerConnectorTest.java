package com.example.hms.service.integration.partner;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.probe.Probe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Coverage for the four MVP-3b stub partner connectors + their shared
 * {@link StubPartnerConnector} scaffolding. Spot-checks each subclass's
 * {@code integrationId} so a refactor that breaks the recorder key
 * surfaces here, and verifies the shared probe/resync semantics
 * through a representative subclass.
 */
@ExtendWith(MockitoExtension.class)
class StubPartnerConnectorTest {

    @Mock private IntegrationHealthRecorder recorder;
    @Mock private IntegrationMessageRecorder messageRecorder;

    @Test
    void nhisReportsExpectedIntegrationId() {
        assertThat(new NhisConnector(recorder, messageRecorder).integrationId()).isEqualTo("partner.nhis");
    }

    @Test
    void nhiaReportsExpectedIntegrationId() {
        assertThat(new NhiaConnector(recorder, messageRecorder).integrationId()).isEqualTo("partner.nhia");
    }

    @Test
    void cnamgsReportsExpectedIntegrationId() {
        assertThat(new CnamgsConnector(recorder, messageRecorder).integrationId()).isEqualTo("partner.cnamgs");
    }

    @Test
    void mutuelleReportsExpectedIntegrationId() {
        assertThat(new MutuelleConnector(recorder, messageRecorder).integrationId()).isEqualTo("partner.mutuelle");
    }

    @Test
    void probeReturnsStubModeFailureWithActionableMessage() {
        NhisConnector nhis = new NhisConnector(recorder, messageRecorder);
        Probe probe = nhis.probe();

        assertThat(probe.ok()).isFalse();
        assertThat(probe.latencyMs()).isZero();
        assertThat(probe.message()).contains("stub mode").contains("partner.nhis");
        // Probes are pure — no health-recorder interaction (the action
        // service records the probe outcome on the caller's behalf).
        verifyNoInteractions(recorder);
    }

    @Test
    void probeRecordsBridgesStyleStubMessageAsFAILED() {
        // MVP-c3 — stub probes also leave a synthetic OUTBOUND/FAILED
        // row in the per-message log so the operator's message-trace
        // search shows the gap clearly.
        new NhisConnector(recorder, messageRecorder).probe();

        verify(messageRecorder).record(
            eq("partner.nhis"), eq(null),
            eq(IntegrationMessageDirection.OUTBOUND),
            eq("PROBE"),
            any(),
            eq(IntegrationMessageStatus.FAILED),
            any());
    }

    @Test
    void resyncRecordsSyntheticSuccessAgainstTheRecorder() {
        UUID orgId = UUID.randomUUID();
        new CnamgsConnector(recorder, messageRecorder).resync(orgId);

        verify(recorder).recordSuccess("partner.cnamgs", orgId, null);
        // MVP-c3 — re-sync also writes an OUTBOUND/SENT row to the
        // message log so the operator can confirm a re-sync happened.
        verify(messageRecorder).record(
            eq("partner.cnamgs"), eq(orgId),
            eq(IntegrationMessageDirection.OUTBOUND),
            eq("RESYNC"),
            any(),
            eq(IntegrationMessageStatus.SENT),
            eq(null));
    }

    @Test
    void resyncIsNullSafeOnOrgId() {
        // Stub connectors never look at the org id directly — passing
        // null mirrors a system-wide re-sync that doesn't target a
        // specific tenant.
        new MutuelleConnector(recorder, messageRecorder).resync(null);

        verify(recorder).recordSuccess("partner.mutuelle", null, null);
    }
}
