package com.example.hms.service.integration.probe;

/**
 * Connectivity-probe SPI for partner / platform integrations
 * (MVP-c batch — MVP-3b "Test connection" action).
 *
 * <p>Implementing beans are picked up by
 * {@code SuperAdminIntegrationHealthService} via
 * {@link #integrationId} and called when an operator clicks Test
 * connection on the Integration Health Console. The result is
 * recorded through the existing {@code IntegrationHealthRecorder}.
 *
 * <p>Probes should be cheap (single round-trip / no business logic);
 * if the partner has no probe endpoint, return
 * {@code Probe.failed("Connector in stub mode — partner protocol
 * not yet wired")} so the UI surfaces the gap clearly.
 */
public interface IntegrationConnectivityProbe {

    /** The {@code integration_id} this probe targets — must match the recorder key. */
    String integrationId();

    Probe probe();
}
