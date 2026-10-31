package com.example.hms.mapper;

import com.example.hms.model.LabTestReferenceRange;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Round 18: which of a test's reference ranges apply to a patient, best first.
 *
 * <p>Before this, the grading took the first range in the result's unit,
 * whatever the patient's sex or age: a man's haemoglobin was graded on the
 * women's range configured first, and a child's on the adult range. The
 * stored ranges always had {@code gender}, {@code ageMin} and {@code ageMax};
 * nothing read them.
 *
 * <p>The rules, read by {@link LabResultMapper}'s one grading selection:
 * <ul>
 *   <li>A range with sex {@code MALE}/{@code FEMALE} applies to that sex
 *       only; {@code ALL} or none applies to everyone.</li>
 *   <li>A range's age bounds are inclusive, in its {@code ageUnit}
 *       ({@code YEARS} when absent, {@code MONTHS}, {@code DAYS}), against the
 *       patient's completed age in that unit on the result date.</li>
 *   <li>The best range wins: a sex-specific match before an {@code ALL}
 *       range, an age band containing the patient before an unbounded range,
 *       then the configured order.</li>
 *   <li>Missing data never hides a result: an unknown sex or date of birth
 *       matches any range, ranked below a real match; and the ranges that do
 *       not apply follow the applicable ones in configured order (all of them,
 *       when none applies: a child, but only adult ranges configured), so the
 *       unit rule can always fall back and a critical value is still graded
 *       and alerted.</li>
 * </ul>
 */
public final class LabRangeApplicability {

    private static final Set<String> FEMALE = Set.of("f", "female", "femme", "feminin", "féminin");
    private static final Set<String> MALE = Set.of("m", "male", "homme", "masculin");

    private LabRangeApplicability() {
    }

    /** The patient facts a range is chosen by; any of them may be unknown (null). */
    public record Demographics(String sex, LocalDate dateOfBirth, LocalDate onDate) {

        /** From the stored gender text and date of birth, on {@code onDate} (today when null). */
        public static Demographics of(String gender, LocalDate dateOfBirth, LocalDate onDate) {
            return new Demographics(sexOf(gender), dateOfBirth, onDate != null ? onDate : LocalDate.now(java.time.ZoneId.systemDefault()));
        }
    }

    /** MALE, FEMALE, or null when the stored text names neither. */
    public static String sexOf(String gender) {
        String normalized = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);
        if (MALE.contains(normalized)) {
            return "MALE";
        }
        if (FEMALE.contains(normalized)) {
            return "FEMALE";
        }
        return null;
    }

    /**
     * The ranges in the order the grading should try them: those that apply to
     * the patient, best first; or, when none applies, all of them in their
     * configured order. {@code null} demographics (no patient loaded) keep the
     * configured order.
     */
    public static List<LabTestReferenceRange> candidates(List<LabTestReferenceRange> ranges,
                                                         Demographics patient) {
        if (ranges == null || ranges.isEmpty() || patient == null) {
            return ranges == null ? List.of() : ranges;
        }
        List<Ranked> applicable = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            LabTestReferenceRange range = ranges.get(i);
            if (range == null) {
                continue;
            }
            int sex = sexRank(range, patient.sex());
            int age = ageRank(range, patient);
            if (sex >= 0 && age >= 0) {
                applicable.add(new Ranked(range, sex + age, i));
            }
        }
        if (applicable.isEmpty()) {
            return ranges;
        }
        applicable.sort(Comparator.comparingInt(Ranked::rank).reversed().thenComparingInt(Ranked::order));
        // Review of round 18: the ranges that do not apply follow, in their
        // configured order, so the unit rule can still fall back to them when
        // no applicable range is in the result's unit: a critical value must
        // never become "not graded: units differ" because of the filter.
        List<LabTestReferenceRange> ordered = new ArrayList<>(applicable.stream().map(Ranked::range).toList());
        for (LabTestReferenceRange range : ranges) {
            if (range != null && !ordered.contains(range)) {
                ordered.add(range);
            }
        }
        return ordered;
    }

    private record Ranked(LabTestReferenceRange range, int rank, int order) {}

    /** 2 the patient's own sex, 1 a range for everyone, 0 the patient's sex unknown, -1 the other sex. */
    private static int sexRank(LabTestReferenceRange range, String patientSex) {
        String rangeSex = sexOf(range.getGender());
        if (rangeSex == null) {
            return 1;
        }
        if (patientSex == null) {
            return 0;
        }
        return rangeSex.equals(patientSex) ? 2 : -1;
    }

    /** 2 inside the range's age band, 1 no band, 0 the patient's age unknown, -1 outside the band. */
    private static int ageRank(LabTestReferenceRange range, Demographics patient) {
        Integer min = range.getAgeMin();
        Integer max = range.getAgeMax();
        boolean bounded = (min != null && min > 0) || max != null;
        if (!bounded) {
            return 1;
        }
        Long age = ageIn(range.getAgeUnit(), patient.dateOfBirth(), patient.onDate());
        if (age == null) {
            return 0;
        }
        boolean inside = (min == null || age >= min) && (max == null || age <= max);
        return inside ? 2 : -1;
    }

    /** Completed age in the unit (YEARS when null or unrecognised), or null when it cannot be known. */
    public static Long ageIn(String unit, LocalDate dateOfBirth, LocalDate onDate) {
        if (dateOfBirth == null || onDate == null || onDate.isBefore(dateOfBirth)) {
            return null;
        }
        String u = unit == null ? "YEARS" : unit.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "DAYS" -> ChronoUnit.DAYS.between(dateOfBirth, onDate);
            case "MONTHS" -> ChronoUnit.MONTHS.between(dateOfBirth, onDate);
            default -> ChronoUnit.YEARS.between(dateOfBirth, onDate);
        };
    }
}
