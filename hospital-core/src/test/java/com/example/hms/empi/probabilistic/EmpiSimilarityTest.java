package com.example.hms.empi.probabilistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-field similarity tests for the row-25 EMPI scorer. The
 * composite-score behaviour is in {@code EmpiProbabilisticMatcherTest};
 * these pin the building blocks against textbook examples so a future
 * Jaro-Winkler swap or DOB-tolerance retune is testable in isolation.
 */
class EmpiSimilarityTest {

    @Test
    @DisplayName("nameSimilarity — identical strings score 1.0, completely different score ~0")
    void nameIdenticalAndDisjoint() {
        assertThat(EmpiSimilarity.nameSimilarity("Awa", "Awa")).isEqualTo(1.0);
        assertThat(EmpiSimilarity.nameSimilarity("xyz", "ABC")).isCloseTo(0.0, within(0.05));
    }

    @Test
    @DisplayName("nameSimilarity — case + whitespace insensitive")
    void nameNormalisation() {
        assertThat(EmpiSimilarity.nameSimilarity("  AWA  ", "awa")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nameSimilarity — single-char typo on a short name still scores high")
    void nameSingleCharTypo() {
        // "Awa" vs "Aua" — distance 1 of length 3 → 1 - 1/3 ≈ 0.667
        assertThat(EmpiSimilarity.nameSimilarity("Awa", "Aua"))
            .isCloseTo(0.667, within(0.005));
    }

    @Test
    @DisplayName("nameSimilarity — null / blank on either side collapses to 0")
    void nameNullAndBlank() {
        assertThat(EmpiSimilarity.nameSimilarity(null, "Awa")).isZero();
        assertThat(EmpiSimilarity.nameSimilarity("Awa", "")).isZero();
        assertThat(EmpiSimilarity.nameSimilarity("   ", "Awa")).isZero();
    }

    @Test
    @DisplayName("combinedNameSimilarity weights last-name 0.6 + first-name 0.4")
    void combinedNameWeights() {
        // first identical (1.0), last completely different (~0): weighted 0.4 × 1.0 + 0.6 × 0 ≈ 0.40
        double score = EmpiSimilarity.combinedNameSimilarity("Awa", "Diallo", "Awa", "Smith");
        // last-name similarity isn't exactly 0 because of shared chars; we
        // assert the weighted sum stays well below 0.5.
        assertThat(score).isLessThan(0.5).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("combinedNameSimilarity — only last present on one side falls back to last-only")
    void combinedNameAsymmetricMissing() {
        double score = EmpiSimilarity.combinedNameSimilarity(
            "", "Diallo", null, "Diallo");
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("dobSimilarity — exact 1.0, same year+month 0.85, same year 0.6, within 1y 0.4, else 0")
    void dobLadder() {
        LocalDate base = LocalDate.of(1990, 6, 15);
        assertThat(EmpiSimilarity.dobSimilarity(base, base)).isEqualTo(1.0);
        assertThat(EmpiSimilarity.dobSimilarity(base, LocalDate.of(1990, 6, 1))).isEqualTo(0.85);
        assertThat(EmpiSimilarity.dobSimilarity(base, LocalDate.of(1990, 12, 31))).isEqualTo(0.6);
        assertThat(EmpiSimilarity.dobSimilarity(base, LocalDate.of(1991, 6, 15))).isEqualTo(0.4);
        assertThat(EmpiSimilarity.dobSimilarity(base, LocalDate.of(1995, 6, 15))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("dobSimilarity — null on either side collapses to 0")
    void dobNull() {
        assertThat(EmpiSimilarity.dobSimilarity(null, LocalDate.now())).isZero();
        assertThat(EmpiSimilarity.dobSimilarity(LocalDate.now(), null)).isZero();
    }

    @Test
    @DisplayName("sexSimilarity — exact case-insensitive match → 1.0, mismatch → 0.0, blank → 0.0")
    void sexExactCaseInsensitive() {
        assertThat(EmpiSimilarity.sexSimilarity("F", "f")).isEqualTo(1.0);
        assertThat(EmpiSimilarity.sexSimilarity("F", "M")).isZero();
        assertThat(EmpiSimilarity.sexSimilarity("", "F")).isZero();
    }

    @Test
    @DisplayName("nationalIdSimilarity — exact match → 1.0, case-SENSITIVE (national IDs are case-meaningful)")
    void nationalIdExactCaseSensitive() {
        assertThat(EmpiSimilarity.nationalIdSimilarity("BF1234567890", "BF1234567890")).isEqualTo(1.0);
        assertThat(EmpiSimilarity.nationalIdSimilarity("bf1234567890", "BF1234567890")).isZero();
        assertThat(EmpiSimilarity.nationalIdSimilarity(null, "BF1234567890")).isZero();
    }
}
