package com.example.hms.service.support;

import com.example.hms.model.education.PatientEducationProgress;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which progress row IS a patient's progress on a resource, when there is
 * more than one.
 *
 * <p>{@code clinical.patient_education_progress} had no unique key on
 * (patient, resource) until V176, so duplicates existed. The list used to return every row
 * and let each client keep the most recently accessed one, while the write
 * ({@code PUT /me/patient/education/{id}/progress}) updated the NEWEST-CREATED
 * one - so with duplicates a rating on a completed item landed on a different
 * row than the one on screen and could flip the item back to "to read" on the
 * web and both apps (#708). Both sides now pick the same row, here, on the
 * server: the most recently accessed, then the most recently created, then
 * the id - total, so the choice never depends on the order a query returned.
 *
 * <p>Since V176 there is only ever one: the migration folded each group of
 * duplicates into the row this rule picks (the same ordering, written in SQL,
 * including {@code java.util.UUID}'s signed id order) and added
 * {@code uk_patient_education_progress_patient_resource}. The readers keep
 * going through here anyway; with one row it is the identity, and if this
 * ordering ever changes, V176's comment records which row was kept.
 */
public final class EducationProgressRows {

    /** Most recently accessed first; never-accessed rows last; then newest; then id. */
    private static final Comparator<PatientEducationProgress> CANONICAL_FIRST = Comparator
        .comparing(PatientEducationProgress::getLastAccessedAt,
            Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
        .thenComparing(PatientEducationProgress::getCreatedAt,
            Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
        .thenComparing(PatientEducationProgress::getId,
            Comparator.nullsLast(Comparator.<UUID>naturalOrder()));

    private EducationProgressRows() {
    }

    /** The row that stands for this (patient, resource), or empty when there is none. */
    public static Optional<PatientEducationProgress> canonical(List<PatientEducationProgress> rowsForOneResource) {
        return rowsForOneResource.stream().min(CANONICAL_FIRST);
    }

    /**
     * One row per resource - the canonical one - most recently accessed first,
     * which is the order the list has always been shown in.
     */
    public static List<PatientEducationProgress> onePerResource(List<PatientEducationProgress> rows) {
        List<PatientEducationProgress> sorted = new ArrayList<>(rows);
        sorted.sort(CANONICAL_FIRST);
        Set<UUID> seen = new HashSet<>();
        return sorted.stream().filter(row -> seen.add(row.getResourceId())).toList();
    }
}
