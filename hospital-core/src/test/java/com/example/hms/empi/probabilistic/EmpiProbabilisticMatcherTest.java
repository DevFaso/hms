package com.example.hms.empi.probabilistic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EmpiProbabilisticMatcher}. The foundation
 * pass returns an empty list unconditionally so the scorer body can
 * land in the follow-on alongside its labelled audit set; these
 * tests pin that contract so a half-implementation cannot ship
 * silently.
 */
class EmpiProbabilisticMatcherTest {

    private EmpiProbabilisticProperties properties;
    private EmpiProbabilisticMatcher matcher;

    @BeforeEach
    void setUp() {
        properties = new EmpiProbabilisticProperties();
        matcher = new EmpiProbabilisticMatcher(properties);
    }

    @Test
    @DisplayName("isEnabled reflects the configuration property")
    void isEnabledReflectsProperty() {
        assertThat(matcher.isEnabled()).isFalse();
        properties.setEnabled(true);
        assertThat(matcher.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("findCandidates returns empty list when flag off")
    void emptyWhenFlagOff() {
        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    @Test
    @DisplayName("findCandidates returns empty list when query is null")
    void emptyWhenQueryNull() {
        properties.setEnabled(true);
        assertThat(matcher.findCandidates(null)).isEmpty();
    }

    @Test
    @DisplayName("findCandidates returns empty list even when flag on (scorer deferred to follow-on)")
    void emptyWhenFlagOnButScorerNotImplemented() {
        properties.setEnabled(true);
        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    private static EmpiCandidateQueryDTO sampleQuery() {
        return new EmpiCandidateQueryDTO(
            "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F", "BF1234567890"
        );
    }
}
