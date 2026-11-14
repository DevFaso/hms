package com.example.hms.service.maternity;

import com.example.hms.repository.DeliveryRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D1890 (provisional, 2026-11-14): the one rule for severe blood pressure after birth. */
class PostpartumSevereHypertensionTest {

    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final PostpartumSevereHypertension rule = new PostpartumSevereHypertension(deliveries);

    @ParameterizedTest(name = "{0}/{1} severe={2}")
    @CsvSource({
        // The user's examples: both QA readings, each threshold alone, and just below both.
        "162,112,true", "164,114,true", "160,80,true", "120,110,true", "159,109,false",
        // Inclusive on each side, either one suffices, a missing value never triggers.
        "160,110,true", "159,110,true", "160,109,true", "200,,true", ",115,true", "159,,false", ",109,false", ",,false"
    })
    void theThresholdsAreInclusiveAndEitherOneSuffices(Integer systolic, Integer diastolic, boolean severe) {
        assertThat(PostpartumSevereHypertension.isSevere(systolic, diastolic)).isEqualTo(severe);
    }

    @Test
    void afterBirthIsARecordedBirthAtOrBeforeTheReadingWithinThePostpartumWindow() {
        UUID mother = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.of(2026, 11, 14, 19, 30);
        when(deliveries.existsByPatient_IdAndBirthDateTimeBetween(mother,
            at.minusDays(PostpartumSevereHypertension.AFTER_BIRTH_LOOKBACK_DAYS), at)).thenReturn(true);

        assertThat(rule.applies(mother, 164, 114, at)).isTrue();
        assertThat(rule.applies(mother, 159, 109, at)).isFalse();
        // No birth on record: no postpartum warning.
        assertThat(rule.applies(UUID.randomUUID(), 164, 114, at)).isFalse();
        assertThat(rule.applies(null, 164, 114, at)).isFalse();
        assertThat(rule.applies(mother, 164, 114, null)).isFalse();
    }

    @Test
    void aReadingBelowTheThresholdsNeverQueriesTheRecord() {
        rule.applies(UUID.randomUUID(), 159, 109, LocalDateTime.now());
        verify(deliveries, never()).existsByPatient_IdAndBirthDateTimeBetween(any(), any(), any());
    }
}
