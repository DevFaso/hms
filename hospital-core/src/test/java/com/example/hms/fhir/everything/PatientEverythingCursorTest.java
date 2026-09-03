package com.example.hms.fhir.everything;

import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download loop follows the bundle's own {@code next} link by parsing
 * {@code _page} back out of it. A silent parse failure would not error —
 * it would truncate the exported record to its first page, which is the
 * worst kind of bug for a file someone treats as "the whole record".
 */
class PatientEverythingCursorTest {

    @Test
    @DisplayName("reads the _page cursor from the next link")
    void readsCursorFromNextLink() {
        Bundle bundle = new Bundle();
        bundle.addLink().setRelation("next")
            .setUrl("Patient/abc/$everything?_page=3&_count=500");
        assertThat(PatientEverythingService.nextCursorOf(bundle)).isEqualTo(3);
    }

    @Test
    @DisplayName("appendNewEntries drops entries already seen on earlier pages")
    void appendNewEntriesDeduplicatesByTypeAndId() {
        // Unpaged sections used to re-emit in full on every cursor
        // iteration; the merge must not let a repeat masquerade as data.
        org.hl7.fhir.r4.model.Condition c1 = new org.hl7.fhir.r4.model.Condition();
        c1.setId("cond-1");
        org.hl7.fhir.r4.model.Condition c1Again = new org.hl7.fhir.r4.model.Condition();
        c1Again.setId("cond-1");
        org.hl7.fhir.r4.model.Condition c2 = new org.hl7.fhir.r4.model.Condition();
        c2.setId("cond-2");

        Bundle merged = new Bundle();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Bundle first = new Bundle();
        first.addEntry().setResource(c1);
        PatientEverythingService.appendNewEntries(merged, first, seen);

        Bundle second = new Bundle();
        second.addEntry().setResource(c1Again);
        second.addEntry().setResource(c2);
        PatientEverythingService.appendNewEntries(merged, second, seen);

        assertThat(merged.getEntry()).hasSize(2);
        assertThat(merged.getEntry().stream()
                .map(e -> e.getResource().getIdElement().getIdPart()))
            .containsExactly("cond-1", "cond-2");
    }

    @Test
    @DisplayName("no next link, or one without a _page, means the loop stops")
    void missingOrMalformedCursorStopsTheLoop() {
        assertThat(PatientEverythingService.nextCursorOf(new Bundle())).isNull();

        Bundle selfOnly = new Bundle();
        selfOnly.addLink().setRelation("self").setUrl("Patient/abc/$everything?_page=9");
        assertThat(PatientEverythingService.nextCursorOf(selfOnly)).isNull();

        Bundle noPage = new Bundle();
        noPage.addLink().setRelation("next").setUrl("Patient/abc/$everything?_count=500");
        assertThat(PatientEverythingService.nextCursorOf(noPage)).isNull();
    }
}
