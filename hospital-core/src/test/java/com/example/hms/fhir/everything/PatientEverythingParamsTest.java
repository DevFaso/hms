package com.example.hms.fhir.everything;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the row-22 follow-on params record. The service-side
 * filter logic is exercised through the existing
 * {@code PatientEverythingServiceTenantGateTest} + the IT suite; here
 * we pin the parser + clamp + null-handling contract.
 */
class PatientEverythingParamsTest {

    @Test
    @DisplayName("of(null,null,null,null) returns defaults: no since, no type filter, DEFAULT_COUNT, cursor 0")
    void defaultsWhenAllArgsNull() {
        PatientEverythingParams p = PatientEverythingParams.of(null, null, null, null);
        assertThat(p.since()).isNull();
        assertThat(p.types()).isEmpty();
        assertThat(p.count()).isEqualTo(PatientEverythingParams.DEFAULT_COUNT);
        assertThat(p.cursor()).isZero();
    }

    @Test
    @DisplayName("count clamps to MAX_COUNT when caller asks for more")
    void clampsCountAboveMax() {
        PatientEverythingParams p = PatientEverythingParams.of(null, null, 9999, null);
        assertThat(p.count()).isEqualTo(PatientEverythingParams.MAX_COUNT);
    }

    @Test
    @DisplayName("non-positive count is rejected at the parser boundary")
    void rejectsNonPositiveCount() {
        assertThatThrownBy(() -> PatientEverythingParams.of(null, null, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("_count");
    }

    @Test
    @DisplayName("negative cursor is rejected at the parser boundary")
    void rejectsNegativeCursor() {
        assertThatThrownBy(() -> PatientEverythingParams.of(null, null, null, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("_page");
    }

    @Test
    @DisplayName("parseTypeList tolerates blank / whitespace / null without throwing")
    void parseTypeListIsLenient() {
        assertThat(PatientEverythingParams.parseTypeList(null)).isEmpty();
        assertThat(PatientEverythingParams.parseTypeList("")).isEmpty();
        assertThat(PatientEverythingParams.parseTypeList("   ")).isEmpty();
        assertThat(PatientEverythingParams.parseTypeList(",,"))
            .as("blank tokens between commas are filtered out, not retained as empty strings")
            .isEmpty();
    }

    @Test
    @DisplayName("parseTypeList trims tokens + preserves caller-supplied order")
    void parseTypeListNormalises() {
        Set<String> types = PatientEverythingParams.parseTypeList(" Encounter , Observation,Patient");
        assertThat(types).containsExactly("Encounter", "Observation", "Patient");
    }

    @Test
    @DisplayName("includes() returns true on empty type set (no filter)")
    void includesWhenNoTypeFilter() {
        PatientEverythingParams p = PatientEverythingParams.of(null, Set.of(), null, null);
        assertThat(p.includes("Encounter")).isTrue();
        assertThat(p.includes("Anything")).isTrue();
    }

    @Test
    @DisplayName("includes() returns true only for caller-listed types when filter is non-empty")
    void includesRespectsTypeFilter() {
        PatientEverythingParams p = PatientEverythingParams.of(null, Set.of("Encounter", "Patient"), null, null);
        assertThat(p.includes("Encounter")).isTrue();
        assertThat(p.includes("Patient")).isTrue();
        assertThat(p.includes("Observation")).isFalse();
    }

    @Test
    @DisplayName("afterSince() — null since OR null lastUpdated passes the filter")
    void afterSinceNullPassthrough() {
        PatientEverythingParams noSince = PatientEverythingParams.of(null, null, null, null);
        assertThat(noSince.afterSince(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();

        PatientEverythingParams withSince = PatientEverythingParams.of(
            Instant.parse("2026-05-01T00:00:00Z"), null, null, null);
        assertThat(withSince.afterSince(null))
            .as("null lastUpdated passes — legacy rows pre-dating BaseEntity.updatedAt aren't silently dropped")
            .isTrue();
    }

    @Test
    @DisplayName("afterSince() — rejects timestamps strictly before since, accepts at-or-after")
    void afterSinceBoundary() {
        Instant since = Instant.parse("2026-05-17T12:00:00Z");
        PatientEverythingParams p = PatientEverythingParams.of(since, null, null, null);
        assertThat(p.afterSince(Instant.parse("2026-05-17T11:59:59Z"))).isFalse();
        assertThat(p.afterSince(since)).isTrue();
        assertThat(p.afterSince(Instant.parse("2026-05-17T12:00:01Z"))).isTrue();
    }

    @Test
    @DisplayName("supportedTypes() advertises the five emitting FHIR types in Bundle-section order")
    void supportedTypesEnumeration() {
        assertThat(PatientEverythingParams.supportedTypes())
            .containsExactly("Patient", "Encounter", "Observation", "Condition", "MedicationRequest");
    }
}
