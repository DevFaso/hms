package com.example.hms.service.integration.partner;

import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.integration.probe.Probe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void nhisReportsExpectedIntegrationId() {
        assertThat(new NhisConnector(recorder).integrationId()).isEqualTo("partner.nhis");
    }

    @Test
    void nhiaReportsExpectedIntegrationId() {
        assertThat(new NhiaConnector(recorder).integrationId()).isEqualTo("partner.nhia");
    }

    @Test
    void cnamgsReportsExpectedIntegrationId() {
        assertThat(new CnamgsConnector(recorder).integrationId()).isEqualTo("partner.cnamgs");
    }

    @Test
    void mutuelleReportsExpectedIntegrationId() {
        assertThat(new MutuelleConnector(recorder).integrationId()).isEqualTo("partner.mutuelle");
    }

    @Test
    void probeReturnsStubModeFailureWithActionableMessage() {
        NhisConnector nhis = new NhisConnector(recorder);
        Probe probe = nhis.probe();

        assertThat(probe.ok()).isFalse();
        assertThat(probe.latencyMs()).isZero();
        assertThat(probe.message()).contains("stub mode").contains("partner.nhis");
        // Probes are pure — no recorder interaction (the action service
        // records the outcome on the caller's behalf).
        verifyNoInteractions(recorder);
    }

    @Test
    void resyncRecordsSyntheticSuccessAgainstTheRecorder() {
        UUID orgId = UUID.randomUUID();
        new CnamgsConnector(recorder).resync(orgId);

        verify(recorder).recordSuccess("partner.cnamgs", orgId, null);
    }

    @Test
    void resyncIsNullSafeOnOrgId() {
        // Stub connectors never look at the org id directly — passing
        // null mirrors a system-wide re-sync that doesn't target a
        // specific tenant.
        new MutuelleConnector(recorder).resync(null);

        verify(recorder).recordSuccess("partner.mutuelle", null, null);
    }
}
