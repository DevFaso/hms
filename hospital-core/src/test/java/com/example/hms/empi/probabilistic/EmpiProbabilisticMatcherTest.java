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

    // ── Branch / condition coverage for the row-25 follow-on ─────────────

    @Test
    @DisplayName("name-prefix block resolves with only firstName provided (lastName blank)")
    void nameBlockFirstNameOnly() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient p = patient(UUID.randomUUID(), "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("Awa", ""))
            .thenReturn(List.of(p));

        var q = new EmpiCandidateQueryDTO("Awa", null, null, null, null);
        assertThat(matcher.findCandidates(q)).hasSize(1);
    }

    @Test
    @DisplayName("name-prefix block resolves with only lastName provided (firstName blank)")
    void nameBlockLastNameOnly() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient p = patient(UUID.randomUUID(), "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("", "Diallo"))
            .thenReturn(List.of(p));

        var q = new EmpiCandidateQueryDTO(null, "Diallo", null, null, null);
        assertThat(matcher.findCandidates(q)).hasSize(1);
    }

    @Test
    @DisplayName("only national-ID provided → alias-block resolves the candidate; name-block is skipped")
    void nationalIdOnlyPath() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        UUID id = UUID.randomUUID();
        Patient p = patient(id, "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(empiService.findIdentityByAlias(any(), any()))
            .thenReturn(Optional.of(identity(id)));
        when(patientRepository.findById(id)).thenReturn(Optional.of(p));

        var q = new EmpiCandidateQueryDTO(null, null, null, null, "BF999");
        List<EmpiCandidateMatchDTO> matches = matcher.findCandidates(q);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).patientId()).isEqualTo(id);
        // The name-prefix block must not be called when both names are blank.
        org.mockito.Mockito.verify(patientRepository, org.mockito.Mockito.never())
            .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any());
    }

    @Test
    @DisplayName("national-ID alias resolves to a patient id NOT in the name block → still added to candidates")
    void nationalIdAliasAddsExtraCandidate() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        UUID byNameId = UUID.randomUUID();
        UUID byAliasId = UUID.randomUUID();
        Patient byName = patient(byNameId, "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        Patient byAlias = patient(byAliasId, "Other", "Person", LocalDate.of(1980, 5, 5), "M");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(byName));
        when(empiService.findIdentityByAlias(any(), any()))
            .thenReturn(Optional.of(identity(byAliasId)));
        when(patientRepository.findById(byAliasId)).thenReturn(Optional.of(byAlias));

        List<EmpiCandidateMatchDTO> matches = matcher.findCandidates(sampleQuery());

        assertThat(matches).extracting(EmpiCandidateMatchDTO::patientId)
            .containsExactlyInAnyOrder(byNameId, byAliasId);
    }

    @Test
    @DisplayName("national-ID alias resolution is invoked ONCE per request, not per candidate (N+1 fix)")
    void aliasLookupHappensOncePerRequest() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient a = patient(UUID.randomUUID(), "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        Patient b = patient(UUID.randomUUID(), "Aua", "Diallo", LocalDate.of(1990, 1, 1), "F");
        Patient c = patient(UUID.randomUUID(), "Aub", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(a, b, c));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        matcher.findCandidates(sampleQuery());

        // Three candidates, but the alias lookup runs ONCE — the
        // previous N+1 path (one lookup per candidate inside score)
        // would fire it three times.
        org.mockito.Mockito.verify(empiService, org.mockito.Mockito.times(1))
            .findIdentityByAlias(any(), any());
    }

    @Test
    @DisplayName("candidate with a null id is dropped from the name block (defensive)")
    void candidateWithNullIdIsSkipped() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient nullId = patient(null, "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(nullId));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    @Test
    @DisplayName("display name falls back to id when both first + last are blank")
    void displayNameFallsBackToId() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        UUID id = UUID.randomUUID();
        Patient p = patient(id, " ", " ", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        var matches = matcher.findCandidates(sampleQuery());
        assertThat(matches).hasSize(1);
        // Both names blank → display name is the UUID string.
        assertThat(matches.get(0).displayName()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("display name uses first-only when last is blank")
    void displayNameFirstOnly() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient p = patient(UUID.randomUUID(), "Awa", "", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        assertThat(matcher.findCandidates(sampleQuery()).get(0).displayName()).isEqualTo("Awa");
    }

    @Test
    @DisplayName("display name uses last-only when first is blank")
    void displayNameLastOnly() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        Patient p = patient(UUID.randomUUID(), "", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        assertThat(matcher.findCandidates(sampleQuery()).get(0).displayName()).isEqualTo("Diallo");
    }

    @Test
    @DisplayName("maxCandidates = 0 returns an empty list even with high-scoring matches")
    void zeroMaxCandidatesTruncatesToEmpty() {
        properties.setEnabled(true);
        properties.setMinScore(0.0);
        properties.setMaxCandidates(0);
        Patient p = patient(UUID.randomUUID(), "Awa", "Diallo", LocalDate.of(1990, 1, 1), "F");
        when(patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(any(), any()))
            .thenReturn(List.of(p));
        when(empiService.findIdentityByAlias(any(), any())).thenReturn(Optional.empty());

        assertThat(matcher.findCandidates(sampleQuery())).isEmpty();
    }

    @Test
    @DisplayName("no name + no nationalId → empty (no blocks fire)")
    void noBlocksMeansEmpty() {
        properties.setEnabled(true);
        var q = new EmpiCandidateQueryDTO(null, null, null, null, null);
        assertThat(matcher.findCandidates(q)).isEmpty();
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
