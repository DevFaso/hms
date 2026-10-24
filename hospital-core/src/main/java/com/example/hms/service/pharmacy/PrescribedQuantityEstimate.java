package com.example.hms.service.pharmacy;

import com.example.hms.model.Prescription;
import com.example.hms.service.impl.MarDoseSchedule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA D706 (third round): the per-fill quantity of a legacy prescription that
 * stores none, read from its regimen as units per dose x doses per day x days.
 *
 * <p>Only an unambiguous regimen is read. Anything else (PRN, a month, a dose
 * that is not a whole number of the medication's strength, prose) yields
 * {@code null}, and the dispense then refuses the fill until a quantity is
 * recorded: a guess must never become a ceiling higher than what was meant.
 */
public final class PrescribedQuantityEstimate {

    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*([A-Z]*)");
    private static final Pattern DURATION =
        Pattern.compile("^(\\d{1,3})\\s*(D|J|DAY|DAYS|JOUR|JOURS|DIA|DIAS|W|WK|WEEK|WEEKS|SEMAINE|SEMAINES|SEMANA|SEMANAS)$");
    private static final Pattern STRENGTH = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(MCG|UG|MG|G)\\b");

    private PrescribedQuantityEstimate() {
    }

    /** Units owed per fill, or null when the regimen cannot be read safely. */
    public static BigDecimal perFill(Prescription prescription) {
        Integer interval = MarDoseSchedule.intervalHours(prescription.getFrequency());
        if (interval == null || 24 % interval != 0) {
            return null;
        }
        Integer days = days(prescription.getDuration());
        BigDecimal unitsPerDose = unitsPerDose(prescription);
        if (days == null || unitsPerDose == null) {
            return null;
        }
        return unitsPerDose.multiply(BigDecimal.valueOf((24L / interval) * days));
    }

    static Integer days(String duration) {
        if (duration == null) {
            return null;
        }
        Matcher m = DURATION.matcher(normalize(duration));
        if (!m.matches()) {
            return null;
        }
        int n = Integer.parseInt(m.group(1));
        int days = m.group(2).startsWith("W") || m.group(2).startsWith("S") ? n * 7 : n;
        return days > 0 && days <= 365 ? days : null;
    }

    static BigDecimal unitsPerDose(Prescription prescription) {
        String dosage = prescription.getDosage();
        if (dosage == null || dosage.isBlank()) {
            return null;
        }
        String text = normalize(dosage);
        if (prescription.getDoseUnit() != null && text.matches("\\d+(?:[.,]\\d+)?")) {
            text = text + " " + normalize(prescription.getDoseUnit());
        }
        Matcher m = AMOUNT.matcher(text);
        if (!m.matches()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
        if (amount.signum() <= 0) {
            return null;
        }
        String unit = m.group(2);
        if (isCountUnit(unit)) {
            return amount;
        }
        BigDecimal doseMg = toMg(amount, unit);
        BigDecimal strengthMg = strengthMg(prescription);
        if (doseMg == null || strengthMg == null || strengthMg.signum() <= 0) {
            return null;
        }
        BigDecimal units = doseMg.divide(strengthMg, 6, RoundingMode.HALF_UP);
        // Only a whole number of units is a regimen the pharmacy can count.
        return units.signum() > 0 && units.stripTrailingZeros().scale() <= 0
            ? units.setScale(0, RoundingMode.UNNECESSARY) : null;
    }

    private static boolean isCountUnit(String unit) {
        return switch (unit) {
            case "CP", "CPS", "COMP", "COMPRIME", "COMPRIMES", "TAB", "TABS", "TABLET", "TABLETS",
                 "COMPRIMIDO", "COMPRIMIDOS", "GELULE", "GELULES", "CAPSULE", "CAPSULES", "CAPSULA",
                 "CAPSULAS", "CAP", "CAPS", "AMPOULE", "AMPOULES", "AMP", "AMPOLLA", "AMPOLLAS",
                 "SACHET", "SACHETS", "SOBRE", "SOBRES", "UNIT", "UNITS", "UNITE", "UNITES",
                 "UNIDAD", "UNIDADES", "SUPPO", "SUPPOSITOIRE", "SUPPOSITOIRES" -> true;
            default -> false;
        };
    }

    private static BigDecimal strengthMg(Prescription prescription) {
        for (String name : new String[] {prescription.getMedicationDisplayName(), prescription.getMedicationName()}) {
            if (name == null) {
                continue;
            }
            Matcher m = STRENGTH.matcher(normalize(name));
            BigDecimal found = null;
            int matches = 0;
            while (m.find()) {
                matches++;
                found = toMg(new BigDecimal(m.group(1).replace(',', '.')), m.group(2));
            }
            // A combination (two strengths) is not one countable strength.
            if (matches == 1) {
                return found;
            }
            if (matches > 1) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal toMg(BigDecimal amount, String unit) {
        return switch (unit) {
            case "MG" -> amount;
            case "G" -> amount.multiply(BigDecimal.valueOf(1000));
            case "MCG", "UG" -> amount.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            default -> null;
        };
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("\\.$", "");
    }
}
