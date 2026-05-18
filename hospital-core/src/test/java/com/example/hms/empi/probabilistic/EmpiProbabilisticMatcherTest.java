package com.example.hms.empi.probabilistic;

import com.example.hms.model.Patient;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmpiProbabilisticMatcher}. Covers the
 * row-25 follow-on scorer body: weighted-sum composite, candidate
 * ranking, threshold filtering, max-candidates truncation. The
 * existing flag-off contract from the foundation pass still holds.
 */
@ExtendWith(MockitoExtension.class)
class EmpiProbabilisticMatcherTest {

    @Mock private PatientRepository patientRepository;
    @Mock private EmpiService empiService;

    private EmpiProbabilisticProperties properties;
    private EmpiProbabilisticMatcher matcher;

    @BeforeEach
    void setUp() {
        properties = new EmpiProbabilisticProperties();
        matcher = new EmpiProbabilisticMatcher(properties, patientRepository, empiService);
    }

    @Test
    @DisplayName("isEnabled reflects the configuration property")
    void isEnabledReflectsProperty() {
        assertThat(matcher.isEnabled()).isFalse();
        properties.setEnabled(true);
        assertThat(matcher.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("flag off → empty list, no repository / EMPI lookups")
    void emptyWhenFlagOff() {
        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    @Test
    @DisplayName("null query → empty list even with the flag on")
    void emptyWhenQueryNull() {
        properties.setEnabled(true);
        assertThat(matcher.findCandidates(null)).isEmpty();
    }

    @Test
    @DisplayName("flag on, no candidates from the name-prefix block → empty list")
    void emptyWhenNoCandidates() {
        properties.setEnabled(true);
        lenient().when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of());
        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    @Test
    @DisplayName("exact-match patient scores 1.0 + returns above the 0.7 default threshold")
    void exactMatchScoresHigh() {
        properties.setEnabled(true);
        UUID id = UUID.randomUUID();
        Patient p = patient(id, "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        // National-ID alias resolves to the SAME patient — boosts the
        // national-ID similarity to 1.0.
        when(empiService.findIdentityByAlias(any(), any()))
            .thenReturn(Optional.of(identity(id)));

        List<EmpiCandidateMatchDTO> matches = matcher.findCandidates(sampleQuery());

        assertThat(matches).hasSize(1);
        EmpiCandidateMatchDTO match = matches.get(0);
        assertThat(match.patientId()).isEqualTo(id);
        assertThat(match.score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.005));
        assertThat(match.nameMatched()).isTrue();
        assertThat(match.dobMatched()).isTrue();
        assertThat(match.sexMatched()).isTrue();
        assertThat(match.nationalIdMatched()).isTrue();
    }

    @Test
    @DisplayName("partial match (different DOB year + different national-ID) drops below threshold")
    void partialMatchBelowThreshold() {
        properties.setEnabled(true);
        properties.setMinScore(0.7);
        Patient p = patient(UUID.randomUUID(), "Awa", "Diallo",
            LocalDate.of(1985, 6, 12), "F");  // 5y off, different month
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        // No alias match for the national ID — similarity 0.
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        List<EmpiCandidateMatchDTO> matches = matcher.findCandidates(sampleQuery());

        // Name = 1.0 (0.4 weight = 0.40), DOB = 0 (5y > 1y window, 0
        // weight), sex = 1.0 (0.10 weight), nationalId = 0 → composite
        // 0.50 — below the default 0.70 threshold, no candidate.
        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("candidates sorted descending by score + truncated to maxCandidates")
    void rankingAndTruncation() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        properties.setMaxCandidates(2);

        Patient a = patient(UUID.randomUUID(), "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        Patient b = patient(UUID.randomUUID(), "Aua", "Diallo", LocalDate.of(1990, 1, 1), "F"); // slight typo
        Patient c = patient(UUID.randomUUID(), "Different", "Person", LocalDate.of(1970, 1, 1), "M");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(c, b, a));  // out-of-order intentionally
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        List<EmpiCandidateMatchDTO> matches = matcher.findCandidates(sampleQuery());

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).patientId()).isEqualTo(a.getId());
        assertThat(matches.get(0).score()).isGreaterThan(matches.get(1).score());
    }

    private static EmpiCandidateQueryDTO sampleQuery() {
        return new EmpiCandidateQueryDTO(
            "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F", "BF1234567890"
        );
    }

    private static Patient patient(UUID id, String first, String last, LocalDate dob, String sex) {
        Patient p = new Patient();
        p.setId(id);
        p.setFirstName(first);
        p.setLastName(last);
        p.setDateOfBirth(dob);
        p.setGender(sex);
        return p;
    }

    private static EmpiIdentityResponseDTO identity(UUID patientId) {
        return EmpiIdentityResponseDTO.builder().patientId(patientId).build();
    }
}
