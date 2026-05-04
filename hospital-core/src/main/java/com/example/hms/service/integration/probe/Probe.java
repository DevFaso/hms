package com.example.hms.service.integration.probe;

/**
 * Outcome of a connector probe (MVP-c batch — MVP-3b).
 *
 * <p>{@code ok=true} signals the partner endpoint is reachable + the
 * handshake succeeded. {@code latencyMs} captures the probe round-trip
 * for the time-series history. {@code message} on failure is surfaced
 * verbatim to the operator UI; on success it's a short status string.
 */
public record Probe(boolean ok, long latencyMs, String message) {

    public static Probe ok(long latencyMs) {
        return new Probe(true, latencyMs, "OK");
    }

    public static Probe ok(long latencyMs, String message) {
        return new Probe(true, latencyMs, message);
    }

    public static Probe failed(String message) {
        return new Probe(false, 0L, message);
    }

    public static Probe failed(long latencyMs, String message) {
        return new Probe(false, latencyMs, message);
    }
}
