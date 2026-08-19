package com.example.hms.service.integration.partner;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.service.integration.health.IntegrationHealthRecorder;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.probe.IntegrationConnectivityProbe;
import com.example.hms.service.integration.probe.Probe;
import com.example.hms.service.integration.probe.Resyncable;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Shared scaffolding for the four West/Central African partner connector
 * stubs (NHIS / NHIA / CNAMGS / mutuelle) — MVP-c batch / MVP-3b.
 *
 * <p>Each subclass declares its {@code integration_id}; the probe always
 * returns {@code Probe.failed} with a "stub mode" message so the
 * Integration Health Console surfaces the gap clearly. Re-sync emits a
 * recorder success after a configurable simulated delay so the UI
 * exercise path is testable end-to-end without partner credentials.
 *
 * <p>MVP-c3 — also emits a Bridges-style row to the
 * {@link IntegrationMessageRecorder} so the operator's message-trace UI
 * shows a synthetic "stub mode" entry per probe / re-sync. The
 * recorder is optional (constructor-injected null is tolerated) so
 * legacy subclasses don't break before the bean is wired up.
 *
 * <p>Real partner protocols (HL7 / FHIR / proprietary REST) drop in by
 * subclassing this and overriding {@link #probe()} + {@link #resync}.
 */
@Slf4j
public abstract class StubPartnerConnector implements IntegrationConnectivityProbe, Resyncable {

    protected final IntegrationHealthRecorder recorder;
    protected final IntegrationMessageRecorder messageRecorder;

    /**
     * Legacy constructor for subclasses that pre-date MVP-c3. Forwards
     * a null message recorder to the new constructor; the message
     * emission becomes a no-op.
     */
    protected StubPartnerConnector(IntegrationHealthRecorder recorder) {
        this(recorder, null);
    }

    protected StubPartnerConnector(
        IntegrationHealthRecorder recorder,
        IntegrationMessageRecorder messageRecorder
    ) {
        this.recorder = recorder;
        this.messageRecorder = messageRecorder;
    }

    @Override
    public Probe probe() {
        log.debug("[PARTNER-STUB] Probe for {} returning stub-mode failure", integrationId());
        recordStubMessage(null, IntegrationMessageDirection.OUTBOUND,
            "PROBE", IntegrationMessageStatus.FAILED,
            "Stub-mode probe — partner protocol not yet wired");
        return Probe.failed("Connector in stub mode — partner protocol not yet wired. "
            + "Drop in a real " + integrationId() + " adapter to enable Test connection.");
    }

    @Override
    public void resync(UUID organizationId) {
        log.info("[PARTNER-STUB] Re-sync invoked for {} org={} (stub: recording success after no-op)",
            integrationId(), organizationId);
        recorder.recordSuccess(integrationId(), organizationId, null);
        recordStubMessage(organizationId, IntegrationMessageDirection.OUTBOUND,
            "RESYNC", IntegrationMessageStatus.SENT, null);
    }

    private void recordStubMessage(
        UUID organizationId,
        IntegrationMessageDirection direction,
        String messageType,
        IntegrationMessageStatus status,
        String errorMessage
    ) {
        if (messageRecorder == null) {
            return;
        }
        messageRecorder.recordMessage(integrationId(), organizationId, direction, messageType,
            "{\"stub\":true,\"integration\":\"" + integrationId() + "\"}",
            status, errorMessage);
    }
}
