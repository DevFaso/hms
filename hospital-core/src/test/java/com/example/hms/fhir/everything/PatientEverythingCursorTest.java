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
