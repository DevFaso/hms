package com.example.hms.cdshooks.terminology;

import com.example.hms.terminology.TerminologyCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RxNormCodingExtractorTest {

    @Test
    @DisplayName("returns RxCUI when a coding entry advertises the canonical RxNorm system")
    void picksRxNormCodingByCanonicalSystem() {
        Map<String, Object> cc = Map.of(
            "text", "Amoxicillin 500 mg oral capsule",
            "coding", List.of(
                Map.of("system", "http://www.whocc.no/atc", "code", "J01CA04"),
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "308182"),
                Map.of("system", "urn:hms:medication:code", "code", "AMOX-500")
            )
        );

        Optional<String> rxnorm = RxNormCodingExtractor.rxnormFromCodeableConcept(cc);

        assertThat(rxnorm).contains("308182");
    }

    @Test
    @DisplayName("returns empty when no coding entry uses the RxNorm system")
    void emptyWhenNoRxNormCoding() {
        Map<String, Object> cc = Map.of(
            "coding", List.of(
                Map.of("system", "http://www.whocc.no/atc", "code", "J01CA04"),
                Map.of("system", "urn:hms:medication:code", "code", "AMOX-500")
            )
        );

        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(cc)).isEmpty();
    }

    @Test
    @DisplayName("rejects an RxNorm-flagged coding whose code is malformed")
    void rejectsMalformedRxnormValue() {
        Map<String, Object> cc = Map.of(
            "coding", List.of(
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "abc123"),
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "  ")
            )
        );

        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(cc)).isEmpty();
    }

    @Test
    @DisplayName("trims whitespace before validating the RxCUI")
    void trimsWhitespace() {
        Map<String, Object> cc = Map.of(
            "coding", List.of(
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "  308182  ")
            )
        );

        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(cc)).contains("308182");
    }

    @Test
    @DisplayName("returns empty for null, non-map, or coding-less inputs")
    void emptyForUnexpectedShapes() {
        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(null)).isEmpty();
        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept("Amoxicillin")).isEmpty();
        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(Map.of())).isEmpty();
        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(Map.of("coding", List.of()))).isEmpty();
        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(Map.of("coding", "not-a-list"))).isEmpty();
    }

    @Test
    @DisplayName("returns the first valid RxCUI when multiple RxNorm codings are present")
    void picksFirstValidWhenMultipleRxnormCodings() {
        Map<String, Object> cc = Map.of(
            "coding", List.of(
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "abc"),
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "308182"),
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "11289")
            )
        );

        assertThat(RxNormCodingExtractor.rxnormFromCodeableConcept(cc)).contains("308182");
    }

    @Test
    @DisplayName("rxnormFromMedicationRequest unwraps medicationCodeableConcept")
    void unwrapsMedicationRequest() {
        Map<String, Object> mr = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of(
                "coding", List.of(
                    Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", "11289")
                )
            )
        );

        assertThat(RxNormCodingExtractor.rxnormFromMedicationRequest(mr)).contains("11289");
    }

    @Test
    @DisplayName("rxnormFromMedicationRequest is empty when the resource has no codeable concept")
    void emptyWhenNoCodeableConcept() {
        Map<String, Object> mr = Map.of(
            "resourceType", "MedicationRequest",
            "medicationReference", Map.of("display", "Amoxicillin")
        );

        assertThat(RxNormCodingExtractor.rxnormFromMedicationRequest(mr)).isEmpty();
        assertThat(RxNormCodingExtractor.rxnormFromMedicationRequest(null)).isEmpty();
    }
}
