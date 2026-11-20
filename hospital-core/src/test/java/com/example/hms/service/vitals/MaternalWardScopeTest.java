package com.example.hms.service.vitals;

import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Policy 2026-11-20 (provisional), ward reads (QA D1314): who of a ward is under the maternal early
 * warning, in two queries for the whole ward, by the same dates the shared obstetric rule reads.
 */
class MaternalWardScopeTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final UUID HERE = UUID.randomUUID();

    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private final MaternalHistoryRepository histories = mock(MaternalHistoryRepository.class);
    private final MaternalWardScope scope = new MaternalWardScope(deliveries, histories);

    private static Object[] history(UUID patientId, LocalDate byUltrasound, LocalDate recorded, LocalDate lmp) {
        return new Object[]{patientId, byUltrasound, recorded, lmp};
    }

    @Test
    @DisplayName("a history that dates a pregnancy covering today puts her under the warning; one that does not, does not")
    void pregnantByHerMaternalHistory() {
        UUID byDueDate = UUID.randomUUID();
        UUID byLmp = UUID.randomUUID();
        UUID byUltrasound = UUID.randomUUID();
        UUID longPast = UUID.randomUUID();
        UUID noDates = UUID.randomUUID();
        List<UUID> ward = List.of(byDueDate, byLmp, byUltrasound, longPast, noDates);
        when(histories.pregnancyDatesAt(any(), eq(HERE))).thenReturn(List.of(
            history(byDueDate, null, TODAY.plusDays(60), null),
            // LMP 100 days ago: due in 180 days.
            history(byLmp, null, null, TODAY.minusDays(100)),
            // The ultrasound date wins over a recorded one that would be long past.
            history(byUltrasound, TODAY.plusDays(10), TODAY.minusDays(200), null),
            // Due 15 days ago: past the 14 days of post-term grace.
            history(longPast, null, TODAY.minusDays(15), null),
            history(noDates, null, null, null)));
        Set<UUID> obstetric = new HashSet<>();

        Map<UUID, LocalDateTime> births = scope.extend(obstetric, HERE, ward, NOW);

        assertThat(obstetric).containsExactlyInAnyOrder(byDueDate, byLmp, byUltrasound);
        assertThat(births).isEmpty();
    }

    @Test
    @DisplayName("the latest version of a history decides, and a birth since the pregnancy began ends it")
    void theLatestVersionAndARecordedBirth() {
        UUID corrected = UUID.randomUUID();
        UUID delivered = UUID.randomUUID();
        UUID deliveredBefore = UUID.randomUUID();
        when(histories.pregnancyDatesAt(any(), eq(HERE))).thenReturn(List.of(
            // Newest first: the correction says the pregnancy is long over; the older version is not read.
            history(corrected, null, TODAY.minusDays(300), null),
            history(corrected, null, TODAY.plusDays(30), null),
            history(delivered, null, TODAY.plusDays(5), null),
            history(deliveredBefore, null, TODAY.plusDays(100), null)));
        when(deliveries.latestBirthTimesAt(any(), eq(HERE), any())).thenReturn(List.of(
            // She gave birth 60 days ago, inside the pregnancy the history dates: no longer pregnant, no longer postpartum.
            new Object[]{delivered, NOW.minusDays(60)},
            // A birth 250 days ago predates this pregnancy (which began 180 days ago): she is pregnant again.
            new Object[]{deliveredBefore, NOW.minusDays(250)}));
        Set<UUID> obstetric = new HashSet<>();

        Map<UUID, LocalDateTime> births = scope.extend(obstetric, HERE, List.of(corrected, delivered, deliveredBefore), NOW);

        assertThat(obstetric).containsExactly(deliveredBefore);
        assertThat(births).as("neither birth is within 42 days").isEmpty();
    }

    @Test
    @DisplayName("a birth within 42 days is returned to date the pulse table; day 43 is not")
    void theBirthsOfThePostpartumWomen() {
        UUID dayTwo = UUID.randomUUID();
        UUID dayFortyTwo = UUID.randomUUID();
        UUID dayFortyThree = UUID.randomUUID();
        when(deliveries.latestBirthTimesAt(any(), eq(HERE), any())).thenReturn(List.of(
            new Object[]{dayTwo, NOW.minusDays(2)},
            new Object[]{dayFortyTwo, TODAY.minusDays(42).atTime(9, 0)},
            new Object[]{dayFortyThree, TODAY.minusDays(43).atTime(23, 0)}));
        when(histories.pregnancyDatesAt(any(), eq(HERE))).thenReturn(Collections.emptyList());

        Map<UUID, LocalDateTime> births = scope.extend(new HashSet<>(), HERE, List.of(dayTwo, dayFortyTwo, dayFortyThree), NOW);

        assertThat(births).containsOnlyKeys(dayTwo, dayFortyTwo);
        assertThat(births.get(dayTwo)).isEqualTo(NOW.minusDays(2));
    }

    @Test
    @DisplayName("two queries for the whole ward, scoped to the acting hospital; none for an empty ward")
    void twoQueriesHoweverManyPatients() {
        List<UUID> ward = java.util.stream.Stream.generate(UUID::randomUUID).limit(60).toList();

        scope.extend(new HashSet<>(), HERE, ward, NOW);

        verify(deliveries, times(1)).latestBirthTimesAt(eq(ward), eq(HERE), any());
        verify(histories, times(1)).pregnancyDatesAt(ward, HERE);

        MaternalWardScope untouched = new MaternalWardScope(mock(DeliveryRecordRepository.class),
            mock(MaternalHistoryRepository.class));
        assertThat(untouched.extend(new HashSet<>(), HERE, List.of(), NOW)).isEmpty();
        assertThat(untouched.extend(new HashSet<>(), null, ward, NOW)).isEmpty();
        DeliveryRecordRepository idle = mock(DeliveryRecordRepository.class);
        new MaternalWardScope(idle, mock(MaternalHistoryRepository.class)).extend(new HashSet<>(), HERE, List.of(), NOW);
        verifyNoInteractions(idle);
    }
}
