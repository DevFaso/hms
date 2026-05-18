package com.example.hms.fhir.everything;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parsed inputs to {@code Patient/{id}/$everything} (roadmap row 22
 * follow-on). Mirrors the four FHIR R4 search-control parameters the
 * spec defines for the operation:
 *
 * <ul>
 *   <li>{@code _since} — only include resources whose last-updated
 *       timestamp is at or after this instant.</li>
 *   <li>{@code _type} — comma-separated list of FHIR resource type
 *       names to include; absent / blank = all supported types
 *       (Patient / Encounter / Observation / Condition /
 *       MedicationRequest).</li>
 *   <li>{@code _count} — page size per resource section. When unset,
 *       the service-side default (200) applies. The spec allows a
 *       server-side cap; HMS caps at 500 to keep a single Bundle
 *       under the typical HAPI 50 MB serialisation budget.</li>
 *   <li>{@code _page} — opaque cursor offset (HMS-specific name; the
 *       FHIR spec leaves cursor encoding implementation-defined). 0
 *       on the first page; the {@code Bundle.link[next]} URL carries
 *       the next cursor when any resource section returned a full
 *       page.</li>
 * </ul>
 *
 * <p>Invalid {@code _since} / {@code _count} / {@code _page} values
 * are rejected at the parser boundary with the FHIR Bulk Data Access
 * spec's preferred {@code 400 + OperationOutcome(VALUE)} shape — same
 * pattern enforced on {@code FhirBulkExportOperationProvider} per the
 * pr-review-response skill.
 */
public record PatientEverythingParams(
    Instant since,
    Set<String> types,
    int count,
    int cursor
) {

    public static final int DEFAULT_COUNT = 200;
    public static final int MAX_COUNT = 500;

    /**
     * Build a normalised params instance. {@code null} / blank
     * {@code types} resolves to an immutable empty set (interpreted
     * by the service as "no filter"). {@code count} clamps to
     * {@link #DEFAULT_COUNT} when null and to {@link #MAX_COUNT}
     * when too large. {@code cursor} defaults to 0 and is rejected as
     * negative.
     */
    public static PatientEverythingParams of(Instant since, Set<String> types, Integer count, Integer cursor) {
        Set<String> normalisedTypes = (types == null) ? Set.of() : types;
        int normalisedCount = count == null ? DEFAULT_COUNT : count;
        if (normalisedCount <= 0) {
            throw new IllegalArgumentException("_count must be positive");
        }
        if (normalisedCount > MAX_COUNT) {
            normalisedCount = MAX_COUNT;
        }
        int normalisedCursor = cursor == null ? 0 : cursor;
        if (normalisedCursor < 0) {
            throw new IllegalArgumentException("_page cursor must be >= 0");
        }
        return new PatientEverythingParams(since, normalisedTypes, normalisedCount, normalisedCursor);
    }

    /**
     * Parse the comma-separated {@code _type} parameter into a
     * canonical set. Uses the same ReDoS-safe split idiom the
     * pr-review-response skill mandates for FHIR comma-separated
     * input (string-trim + filter empty in a stream, NOT
     * {@code split("\\s*,\\s*")}).
     */
    public static Set<String> parseTypeList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Returns {@code true} when {@link #types()} is empty (caller asked
     * for everything) OR when it explicitly contains {@code resourceType}.
     */
    public boolean includes(String resourceType) {
        return types.isEmpty() || types.contains(resourceType);
    }

    /**
     * Returns {@code true} when {@code lastUpdated} is null OR is at-or-after
     * the configured {@link #since()} threshold. Null timestamps pass the
     * filter — the spec is ambiguous on missing modification metadata,
     * and dropping rows with a null timestamp would silently exclude
     * everything inserted before the platform started populating
     * {@code BaseEntity.updatedAt} (rare, but real for legacy data).
     */
    public boolean afterSince(Instant lastUpdated) {
        if (since == null) return true;
        if (lastUpdated == null) return true;
        return !lastUpdated.isBefore(since);
    }

    /**
     * Used by both the documentation in the runbook and the param-list
     * loop to enumerate which FHIR types $everything is willing to emit.
     */
    public static Set<String> supportedTypes() {
        // LinkedHashSet preserves the order the Bundle emits sections,
        // which is the order downstream consumers tend to render
        // chronologically in their inbox UI.
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "Patient", "Encounter", "Observation", "Condition", "MedicationRequest"
        )));
    }
}
