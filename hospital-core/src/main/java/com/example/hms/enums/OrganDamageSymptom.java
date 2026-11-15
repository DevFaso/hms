package com.example.hms.enums;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Severe-hypertension r54 (PROVISIONAL, user decision of 2026-11-15, pending the clinical lead's
 * approval): the signs of acute organ damage a recorder may tick next to a blood pressure. One ticked
 * sign turns an adult's severe-range warning into an URGENT one; none is ever required to save.
 * Stored on the vital set as a comma-separated list of these names (V299).
 */
public enum OrganDamageSymptom {
    CHEST_PAIN,
    NEUROLOGICAL_SIGNS,
    DYSPNEA,
    VISUAL_DISTURBANCE,
    SEVERE_HEADACHE;

    /** The stored form: the names in declaration order, comma-separated; null for none. */
    public static String join(Collection<OrganDamageSymptom> symptoms) {
        if (symptoms == null || symptoms.isEmpty()) {
            return null;
        }
        EnumSet<OrganDamageSymptom> set = EnumSet.noneOf(OrganDamageSymptom.class);
        symptoms.stream().filter(java.util.Objects::nonNull).forEach(set::add);
        return set.isEmpty() ? null : set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /** The stored form read back; an unknown name is skipped, never thrown. */
    public static Set<OrganDamageSymptom> parse(String stored) {
        EnumSet<OrganDamageSymptom> set = EnumSet.noneOf(OrganDamageSymptom.class);
        if (stored == null || stored.isBlank()) {
            return set;
        }
        for (String part : stored.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            for (OrganDamageSymptom s : values()) {
                if (s.name().equals(name)) {
                    set.add(s);
                }
            }
        }
        return set;
    }
}
