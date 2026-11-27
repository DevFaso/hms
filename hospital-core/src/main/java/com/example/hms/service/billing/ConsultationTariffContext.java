package com.example.hms.service.billing;

import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PriceListSystemKey;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * QA D4221 (round 81): which consultation tariff a visit takes. Every visit used to be billed the hospital's default
 * consultation, so an emergency seen at night, a specialist's visit or a follow-up was billed the general rate
 * unless the cashier corrected the line by hand.
 *
 * <p>The answer is an ordered list of price-list keys, the most specific first, always ending with
 * {@link PriceListSystemKey#CONSULTATION_DEFAULT}: the visit is billed the first one the hospital's own price list
 * holds on the service day. A hospital that links only its general consultation is billed exactly as before. The
 * line stays a draft line the cashier can re-price or replace, with a reason, as any other.
 *
 * <p>Provisional (rule set CONSULTATION_FEE), in this order:
 * <ol>
 *   <li>An emergency visit (its type, or a visit of the emergency department) takes the emergency tariff; the night
 *       tariff when the patient arrived from {@link #NIGHT_FROM} to {@link #NIGHT_UNTIL} or on a Sunday, on the
 *       hospital's clock. Public holidays are not known to the system: the cashier picks that tariff.</li>
 *   <li>A follow-up visit takes the follow-up tariff.</li>
 *   <li>A visit of an antenatal, postnatal, paediatric, gynaecology-obstetrics, surgery or internal medicine
 *       department takes that department's tariff.</li>
 * </ol>
 */
@ProvisionalRule(ClinicalRuleSetKey.CONSULTATION_FEE)
public final class ConsultationTariffContext {

    /** The night tariff applies from this time of the evening ... */
    static final LocalTime NIGHT_FROM = LocalTime.of(20, 0);
    /** ... until this time of the morning (excluded). */
    static final LocalTime NIGHT_UNTIL = LocalTime.of(6, 0);

    private static final Pattern EMERGENCY =
        Pattern.compile("\\b(urgence|urgences|emergency|emergencies|urgencias|urg|sau)\\b");
    private static final Pattern POSTNATAL = Pattern.compile("\\b(cpon|post[- ]?natal\\w*|post[- ]?partum)\\b");
    private static final Pattern PRENATAL = Pattern.compile("\\b(cpn|pre[- ]?natal\\w*|ante[- ]?natal\\w*)\\b");
    private static final Pattern PAEDIATRICS = Pattern.compile("\\b(pa?ediatr\\w*)\\b");
    private static final Pattern GYNAECOLOGY = Pattern.compile("\\b(gyna?eco\\w*|obstetr\\w*)\\b");
    private static final Pattern SURGERY = Pattern.compile("\\b(chirurg\\w*|surg\\w*|cirug\\w*)\\b");
    private static final Pattern INTERNAL_MEDICINE =
        Pattern.compile("\\b(medecine interne|internal medicine|medicina interna)\\b");

    private ConsultationTariffContext() {
    }

    /** The keys of {@code visit}, seen at {@code arrivedAt} on the hospital's clock (null: the hour is unknown). */
    public static List<PriceListSystemKey> keysFor(Encounter visit, LocalDateTime arrivedAt) {
        Department department = visit.getDepartment();
        return keysFor(visit.getEncounterType(), department == null ? null : department.getName(),
            department == null ? null : department.getCode(), arrivedAt);
    }

    /** The same rule from its parts: the visit's type, its department as named and coded, the arrival. */
    public static List<PriceListSystemKey> keysFor(EncounterType type, String departmentName, String departmentCode,
                                                   LocalDateTime arrivedAt) {
        String department = fold(departmentName) + " | " + fold(departmentCode);
        List<PriceListSystemKey> keys = new ArrayList<>();
        if (type == EncounterType.EMERGENCY || EMERGENCY.matcher(department).find()) {
            if (isNightOrSunday(arrivedAt)) {
                keys.add(PriceListSystemKey.CONSULTATION_EMERGENCY_NIGHT);
            }
            keys.add(PriceListSystemKey.CONSULTATION_EMERGENCY);
        } else {
            if (type == EncounterType.FOLLOW_UP) {
                keys.add(PriceListSystemKey.CONSULTATION_FOLLOW_UP);
            }
            PriceListSystemKey ofDepartment = departmentKey(department);
            if (ofDepartment != null) {
                keys.add(ofDepartment);
            }
        }
        keys.add(PriceListSystemKey.CONSULTATION_DEFAULT);
        return List.copyOf(keys);
    }

    /** Whether {@code at} falls in the night window or on a Sunday; false when the hour is unknown. */
    static boolean isNightOrSunday(LocalDateTime at) {
        if (at == null) {
            return false;
        }
        LocalTime time = at.toLocalTime();
        return at.getDayOfWeek() == DayOfWeek.SUNDAY || !time.isBefore(NIGHT_FROM) || time.isBefore(NIGHT_UNTIL);
    }

    /** Postnatal before antenatal and both before gynaecology: the CPON and the CPN of a maternity are their own. */
    private static PriceListSystemKey departmentKey(String department) {
        if (POSTNATAL.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_POSTNATAL;
        }
        if (PRENATAL.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_PRENATAL;
        }
        if (PAEDIATRICS.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_PAEDIATRICS;
        }
        if (GYNAECOLOGY.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_GYNAECOLOGY;
        }
        if (SURGERY.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_SURGERY;
        }
        if (INTERNAL_MEDICINE.matcher(department).find()) {
            return PriceListSystemKey.CONSULTATION_INTERNAL_MEDICINE;
        }
        return null;
    }

    /** Lower case, without accents; empty for null. */
    private static String fold(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
