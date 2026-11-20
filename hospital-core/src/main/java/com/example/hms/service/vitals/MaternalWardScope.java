package com.example.hms.service.vitals;

import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional), for the ward reads (QA D1314): who of a whole ward is under the
 * maternal early warning, in two queries however many patients the ward holds (never one lookup per
 * patient). The ward list already knows the women in labour here or delivered here within 42 days
 * (one query, review of #1237); this adds the births that date the postpartum ones (the pulse table
 * switches 48 h after the birth) and the women a maternal history at this hospital dates as pregnant
 * now, by the same dates the shared rule reads ({@link SevereHypertension#dueDateOf},
 * {@link SevereHypertension#isPregnantOn}, no birth recorded since the pregnancy began).
 *
 * <p>Read at the acting hospital only, as the ward list is: a ward shows the patients in its own beds.
 */
@Component
@RequiredArgsConstructor
public class MaternalWardScope {

    private final DeliveryRecordRepository deliveryRecordRepository;
    private final MaternalHistoryRepository maternalHistoryRepository;

    /**
     * Adds to {@code obstetric} the patients of {@code patientIds} pregnant now by their maternal
     * history, and returns the latest birth of each woman still within 42 days of it.
     */
    public Map<UUID, LocalDateTime> extend(Set<UUID> obstetric, UUID hospitalId, Collection<UUID> patientIds,
                                           LocalDateTime now) {
        if (hospitalId == null || patientIds == null || patientIds.isEmpty() || now == null) {
            return Map.of();
        }
        LocalDate today = now.toLocalDate();
        // A pregnancy that covers today began at most 280 + 14 days ago: no older birth can matter.
        LocalDateTime since = today
            .minusDays((long) SevereHypertension.PREGNANCY_DAYS + SevereHypertension.POST_TERM_GRACE_DAYS).atStartOfDay();
        Map<UUID, LocalDateTime> latestBirth = latestBirths(hospitalId, patientIds, since);
        for (Map.Entry<UUID, LocalDate> history : dueDates(hospitalId, patientIds).entrySet()) {
            if (pregnantOn(today, history.getValue(), latestBirth.get(history.getKey()))) {
                obstetric.add(history.getKey());
            }
        }
        LocalDateTime postpartumFrom = today.minusDays(SevereHypertension.POSTPARTUM_DAYS).atStartOfDay();
        Map<UUID, LocalDateTime> births = new HashMap<>();
        latestBirth.forEach((patientId, birthAt) -> {
            if (!birthAt.isBefore(postpartumFrom) && !birthAt.isAfter(now)) {
                births.put(patientId, birthAt);
            }
        });
        return births;
    }

    /** The latest birth since {@code since} of each patient, in one query. */
    private Map<UUID, LocalDateTime> latestBirths(UUID hospitalId, Collection<UUID> patientIds, LocalDateTime since) {
        Map<UUID, LocalDateTime> latestBirth = new HashMap<>();
        for (Object[] row : deliveryRecordRepository.latestBirthTimesAt(patientIds, hospitalId, since)) {
            if (row[0] instanceof UUID patientId && row[1] instanceof LocalDateTime birthAt) {
                latestBirth.put(patientId, birthAt);
            }
        }
        return latestBirth;
    }

    /**
     * The due date of the latest version of each woman's history (the rows come newest first), in one
     * query. The latest version decides even when it dates nothing: a woman is marked seen on her first
     * row, so an older version never answers in place of a newer one that carries no date.
     */
    private Map<UUID, LocalDate> dueDates(UUID hospitalId, Collection<UUID> patientIds) {
        Map<UUID, LocalDate> dueDates = new HashMap<>();
        Set<UUID> seen = new java.util.HashSet<>();
        for (Object[] row : maternalHistoryRepository.pregnancyDatesAt(patientIds, hospitalId)) {
            if (row[0] instanceof UUID patientId && seen.add(patientId)) {
                dueDates.put(patientId, dueDate((LocalDate) row[1], (LocalDate) row[2], (LocalDate) row[3]));
            }
        }
        return dueDates;
    }

    /** The pregnancy {@code due} dates covers {@code today}, and no birth is recorded since it began. */
    private static boolean pregnantOn(LocalDate today, LocalDate due, LocalDateTime latestBirth) {
        if (!SevereHypertension.isPregnantOn(due, today)) {
            return false;
        }
        return latestBirth == null
            || latestBirth.isBefore(due.minusDays(SevereHypertension.PREGNANCY_DAYS).atStartOfDay());
    }

    /** As {@link SevereHypertension#dueDateOf}: ultrasound first, then the recorded one, then LMP + 280 days. */
    private static LocalDate dueDate(LocalDate byUltrasound, LocalDate recorded, LocalDate lastMenstrualPeriod) {
        if (byUltrasound != null) {
            return byUltrasound;
        }
        if (recorded != null) {
            return recorded;
        }
        return lastMenstrualPeriod == null ? null : lastMenstrualPeriod.plusDays(SevereHypertension.PREGNANCY_DAYS);
    }
}
