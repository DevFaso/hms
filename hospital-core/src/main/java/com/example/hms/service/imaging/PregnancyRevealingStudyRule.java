package com.example.hms.service.imaging;

import com.example.hms.model.ImagingOrder;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * QA D3154 (r70a): the imaging studies a proxy never reads because they may
 * reveal a pregnancy.
 *
 * <p>Pregnancy is a sensitive category (#1242). A study's sensitivity otherwise
 * comes from the visit it was ordered in ({@code ProxyRecordFilter}), but an
 * obstetric ultrasound ordered in an untagged general visit, or in no visit at
 * all, says what the visit does not. Decision (2026-11-21): every study a proxy
 * reads, with or without a visit, is kept from the proxy when what was ordered
 * is an obstetric, pelvic or gynaecological study; this is in addition to the
 * visit's sensitivity, never instead of it. Mammography is not one of them.
 * The patient reads every study.
 *
 * <p><b>PROVISIONAL</b> (« provisoire ») until the clinic's radiologist or
 * medical lead signs it off (docs/qa/regles-cliniques-provisoires.md). The
 * imaging catalogue has no category field, so the study is matched on what the
 * order says it is: its study type, body region and clinical question, read
 * without case or accents, against the bounded list {@link #TERMS} (FR/EN/ES).
 * A match keeps the study with the patient (fails closed); a false match only
 * hides a study from the caregiver, never shows one. The report text is not
 * read.
 */
public final class PregnancyRevealingStudyRule {

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

    private PregnancyRevealingStudyRule() {
    }

    /** True when the order's own description names an obstetric, pelvic or gynaecological study. */
    public static boolean mayRevealPregnancy(ImagingOrder order) {
        if (order == null) {
            return true;
        }
        return Stream.of(order.getStudyType(), order.getBodyRegion(), order.getClinicalQuestion())
            .map(PregnancyRevealingStudyRule::fold)
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
