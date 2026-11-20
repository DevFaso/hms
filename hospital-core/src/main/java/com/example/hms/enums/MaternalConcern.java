package com.example.hms.enums;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Policy 2026-11-20 (PROVISIONAL, pending a named obstetric clinician's sign-off): the "additional
 * concerns" of the NHS England national Maternity Early Warning Score chart (2024). They are recorded
 * flags that raise the level of concern; they never add points to the score. Consciousness is one of
 * them: the national MEWS has no ACVPU row.
 *
 * <p>Source: NHS England, "National MEWS – Final version" (chart hosted on e-LfH,
 * portal.e-lfh.org.uk/ContentServer/content/MEW_01_001/course/en/assets/National-MEWS-Final-version.pdf).
 * Stored on the vital set as a comma-separated list of these names (V348).
 */
public enum MaternalConcern {
    /** The healthcare professional is concerned. */
    STAFF_CONCERNED,
    /** The woman or her family is concerned. */
    WOMAN_OR_FAMILY_CONCERNED,
    /** Significant additional therapies, oxygen for one. Also read from the set's own « on oxygen ». */
    ADDITIONAL_THERAPY,
    /** Increased pain, with or without a need for analgesia. */
    INCREASED_PAIN,
    /** Significant vaginal bleeding. */
    VAGINAL_BLEEDING,
    /** Reduced urine output. */
    REDUCED_URINE_OUTPUT,
    /** Decreased level of consciousness or responsiveness. Also read from the set's own consciousness level. */
    DECREASED_CONSCIOUSNESS;

    /** The stored form: the names in declaration order, comma-separated; null for none. */
    public static String join(Collection<MaternalConcern> concerns) {
        if (concerns == null || concerns.isEmpty()) {
            return null;
        }
        EnumSet<MaternalConcern> set = EnumSet.noneOf(MaternalConcern.class);
        concerns.stream().filter(java.util.Objects::nonNull).forEach(set::add);
        return set.isEmpty() ? null : set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /** The stored form read back; an unknown name is skipped, never thrown. */
    public static Set<MaternalConcern> parse(String stored) {
        EnumSet<MaternalConcern> set = EnumSet.noneOf(MaternalConcern.class);
        if (stored == null || stored.isBlank()) {
            return set;
        }
        for (String part : stored.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            for (MaternalConcern c : values()) {
                if (c.name().equals(name)) {
                    set.add(c);
                }
            }
        }
        return set;
    }
}
