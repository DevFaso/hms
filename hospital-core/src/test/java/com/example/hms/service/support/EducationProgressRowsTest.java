package com.example.hms.service.support;

import com.example.hms.model.education.PatientEducationProgress;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EducationProgressRowsTest {

    private static final UUID RESOURCE_A = UUID.randomUUID();
    private static final UUID RESOURCE_B = UUID.randomUUID();

    private static PatientEducationProgress row(UUID resource, LocalDateTime accessed, LocalDateTime created) {
        PatientEducationProgress p = new PatientEducationProgress();
        p.setId(UUID.randomUUID());
        p.setResourceId(resource);
        p.setLastAccessedAt(accessed);
        p.setCreatedAt(created);
        return p;
    }

    @Test
    void theMostRecentlyAccessedRowStandsForTheResourceWhateverOrderTheQueryReturned() {
        LocalDateTime now = LocalDateTime.now();
        PatientEducationProgress accessed = row(RESOURCE_A, now.minusHours(1), now.minusDays(30));
        PatientEducationProgress newestNeverOpened = row(RESOURCE_A, null, now.minusDays(1));

        assertThat(EducationProgressRows.canonical(List.of(newestNeverOpened, accessed))).containsSame(accessed);
        assertThat(EducationProgressRows.canonical(List.of(accessed, newestNeverOpened))).containsSame(accessed);
    }

    @Test
    void amongNeverOpenedRowsTheNewestStands() {
        LocalDateTime now = LocalDateTime.now();
        PatientEducationProgress older = row(RESOURCE_A, null, now.minusDays(3));
        PatientEducationProgress newer = row(RESOURCE_A, null, now.minusDays(1));

        assertThat(EducationProgressRows.canonical(List.of(older, newer))).containsSame(newer);
    }

    @Test
    void noRowsMeansNotAssigned() {
        assertThat(EducationProgressRows.canonical(List.of())).isEmpty();
    }

    @Test
    void theListKeepsTheSameRowPerResourceMostRecentlyAccessedFirst() {
        LocalDateTime now = LocalDateTime.now();
        PatientEducationProgress aAccessed = row(RESOURCE_A, now.minusHours(5), now.minusDays(30));
        PatientEducationProgress aNewest = row(RESOURCE_A, null, now.minusDays(1));
        PatientEducationProgress bAccessed = row(RESOURCE_B, now.minusHours(1), now.minusDays(10));

        List<PatientEducationProgress> kept =
            EducationProgressRows.onePerResource(List.of(aNewest, aAccessed, bAccessed));

        assertThat(kept).containsExactly(bAccessed, aAccessed);
    }
}
