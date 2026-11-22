package com.example.hms.service.imaging;

import com.example.hms.model.ImagingOrder;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * QA D3154 (r70a): which studies ordered outside any visit a proxy may read.
 *
 * <p>A study's sensitivity comes from the visit it was ordered in
 * ({@code ProxyRecordFilter}). An order that names no visit (ordered when the
 * patient had no open visit, or before the order joined one) has nothing to
 * judge it by, so #1276 hid every one of them from a caregiver: none was ever
 * shown. Decision (2026-11-21, after #1242's rule that pregnancy is
 * sensitive): such a study reaches a proxy, unless what was ordered may reveal
 * a pregnancy: an obstetric, pelvic or gynaecological study. Mammography is
 * not one of them.
 *
 * <p><b>PROVISIONAL</b> (« provisoire ») until the clinic's radiologist or
 * medical lead signs it off (docs/qa/regles-cliniques-provisoires.md). The
 * imaging catalogue has no category field, so the study is matched on what the
 * order says it is: its study type, body region and clinical question, read
 * without case or accents, against the bounded list {@link #TERMS} (FR/EN/ES).
 * A match keeps the study with the patient (fails closed); a false match only
 * hides a study from the caregiver, never shows one.
 */
public final class VisitlessStudyProxyRule {

    /**
     * Word stems of an obstetric, pelvic or gynaecological study, compared
     * without case or accents. Bounded on purpose: a longer list is a clinical
     * decision for the sign-off, not a code change made in passing.
     */
    static final List<String> TERMS = List.of(
        // obstetric / pregnancy
        "obstet", "grossesse", "pregnan", "embaraz", "gestat", "foet", "fœt", "fetal", "fetus", "feto",
        "placent", "amnio", "nucal", "nuchal", "datation", "morpholog",
        // pelvic
        "pelv", "bassin", "douglas",
        // gynaecological
        "gyn", "ginec", "transvag", "endovag", "vagin", "uterus", "uterin", "utero", "ovar", "endometr",
        "salping", "annex", "adnex", "trompe");

    private VisitlessStudyProxyRule() {
    }

    /** True when the order's own description names an obstetric, pelvic or gynaecological study. */
    public static boolean mayRevealPregnancy(ImagingOrder order) {
        if (order == null) {
            return true;
        }
        return Stream.of(order.getStudyType(), order.getBodyRegion(), order.getClinicalQuestion())
            .map(VisitlessStudyProxyRule::fold)
            .anyMatch(text -> TERMS.stream().anyMatch(text::contains));
    }

    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
    }
}
