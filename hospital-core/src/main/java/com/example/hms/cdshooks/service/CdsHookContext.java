package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpers for unpacking the loosely-typed {@code context} and {@code prefetch}
 * payloads in a {@link CdsHookRequest}.
 *
 * <p>The CDS Hooks spec deliberately leaves the inner shapes flexible — we
 * accept FHIR references like {@code "Patient/{uuid}"} as well as bare UUIDs
 * to ease integration with portals that send either form.
 */
public final class CdsHookContext {

    private CdsHookContext() {}

    /**
     * Resolves {@code context.patientId} to a UUID. Returns {@code null} when
     * the request does not carry one (e.g. a discovery probe). Safe across
     * raw String, FHIR reference ({@code Patient/{id}}) and UUID forms.
     */
    public static UUID requirePatientId(CdsHookRequest request) {
        if (request == null || request.context() == null) return null;
        Object raw = request.context().get("patientId");
        return parseUuid(raw);
    }

    public static UUID requireEncounterId(CdsHookRequest request) {
        if (request == null || request.context() == null) return null;
        return parseUuid(request.context().get("encounterId"));
    }

    /**
     * Pulls a list of {@code MedicationRequest}-shaped maps from the
     * {@code context.draftOrders.entry[*].resource} or
     * {@code context.medications.entry[*].resource} structure used by the
     * {@code order-sign} / {@code medication-prescribe} hooks. Returns an
     * empty list rather than null so callers can stream over it directly.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> medicationDrafts(CdsHookRequest request) {
        if (request == null || request.context() == null) return List.of();
        for (String key : List.of("draftOrders", "medications")) {
            Object container = request.context().get(key);
            if (container instanceof Map<?, ?> map) {
                Object entries = map.get("entry");
                if (entries instanceof List<?> list) {
                    return list.stream()
                        .filter(e -> e instanceof Map<?, ?>)
                        .map(e -> ((Map<?, ?>) e).get("resource"))
                        .filter(resource -> resource instanceof Map<?, ?>)
                        .map(resource -> (Map<String, Object>) resource)
                        .toList();
                }
            }
        }
        return List.of();
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
