package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.utility.ObstetricEligibility;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * QA D3154 (r70a), QA D3450 (r70, High): the imaging studies a proxy never
 * reads because they may reveal a pregnancy.
 *
 * <p>Pregnancy is a sensitive category (#1242). A study's sensitivity otherwise
 * comes from the visit it was ordered in ({@code ProxyRecordFilter}), but an
 * obstetric ultrasound ordered in an untagged general visit, or in no visit at
 * all, says what the visit does not. Decision (2026-11-21): such a study is
 * kept from the proxy, in addition to the visit's sensitivity, never instead
 * of it. Mammography is not one of them. The patient reads every study.
 *
 * <p><b>Version 2 (2026-11-22, QA D3450).</b> Version 1 read the order's free
 * text against a bounded word list, and « Échographie du 1er trimestre » of a
 * 15-year-old reached her mother: a word list over free text always misses a
 * wording. The decision now comes from three places, in this order:
 * <ol>
 *   <li><b>The catalogue exam's flag.</b> An order names its exam in the
 *       hospital's imaging catalogue ({@code PriceListItem}, V361), and the
 *       hospital admin or a radiologist says of each exam whether it may reveal
 *       a pregnancy. A flagged exam never reaches a proxy. A cleared exam does,
 *       unless the clinical question typed on that one order names a pregnancy.</li>
 *   <li><b>The words</b> ({@link #TERMS}, {@link #WORDS}), for an exam nobody
 *       decided and for a free-text order: obstetric, pelvic and gynaecological
 *       stems, FR/EN/ES, read without case or accents, whoever the patient is.</li>
 *   <li><b>Fail closed.</b> For a patient whose record does not say male, aged
 *       {@value #FAIL_CLOSED_MIN_AGE} to {@value #FAIL_CLOSED_MAX_AGE} (or of
 *       unknown age), every ultrasound and every abdominal or pelvic study is
 *       kept from the proxy until its catalogue exam is explicitly cleared. It
 *       is then a pregnancy row of that patient: the one reader the
 *       sensitive-category policy lets read such a row (a verified parent or
 *       guardian of a child under the hospital's adolescent age) still reads
 *       it; a flagged or a named study is read by no proxy at all.</li>
 * </ol>
 * A wrong match only hides a study from the caregiver, never shows one. The
 * report text is not read.
 *
 * <p><b>PROVISIONAL</b> (« provisoire ») until the clinic's radiologist or
 * medical lead signs it off (docs/qa/regles-cliniques-provisoires.md). The age
 * band is wider than the WHO's « women of reproductive age » (15 to 49 years)
 * on purpose: the WHO also counts births to girls of 10 to 14, and a wider band
 * only hides more. The word lists stay bounded: a longer list is a decision for
 * the sign-off.
 *
 * <p>No regular expression reads the order's text (the text is typed by a
 * user): it is folded and cut into words by one pass over its characters.
 */
@ProvisionalRule(ClinicalRuleSetKey.PREGNANCY_REVEALING_IMAGING)
public final class PregnancyRevealingStudyRule {

    /** The fail-closed band, in whole years, both ends included. */
    public static final int FAIL_CLOSED_MIN_AGE = 10;
    public static final int FAIL_CLOSED_MAX_AGE = 55;

    /**
     * Word stems of an obstetric, pelvic or gynaecological study, compared
     * without case or accents, anywhere in the text.
     */
    static final List<String> TERMS = List.of(
        // obstetric / pregnancy
        "obstet", "grossesse", "enceinte", "pregnan", "embaraz", "gestat", "foet", "fœt", "fetal", "fetus", "feto",
        "embryo", "placent", "amnio", "nucal", "nuchal", "datation", "dating scan", "morpho", "trimestr",
        "trimester", "amenorr", "biometr", "growth scan", "vitalite", "viabilit", "prenat", "antenat", "ombilic",
        "umbilic", "cervicometr", "partum", "accouch", "cesar", "avort", "abort", "fausse couche", "miscarr",
        "curet", "ovul",
        // pelvic
        "pelv", "bassin", "douglas",
        // gynaecological
        "gyn", "ginec", "transvag", "endovag", "vagin", "uterus", "uterin", "utero", "hyster", "ovar", "endometr",
        "salping", "annex", "adnex", "trompe", "sterilet");

    /**
     * Short obstetric words and abbreviations, matched as whole words only
     * (« col » is not « colonne », « geu » is not « gueule »).
     */
    static final Set<String> WORDS = Set.of("terme", "col", "geu", "rciu", "mfiu", "ivg", "diu", "iud", "cn",
        "lcc", "bip", "ddr", "dpa");

    /** A cervix is a « col »; so is a femoral neck. With one of these in the text, « col » is not read. */
    private static final List<String> NOT_A_CERVIX = List.of("femur", "femor", "humer", "vesic", "chirurgical");

    /** « 12 SA », « 22SA », « 8 SG »: weeks of amenorrhoea or of pregnancy, only after a number. */
    private static final Set<String> WEEK_UNITS = Set.of("sa", "sg");

    /** An ultrasound said in words, when the order's modality is not ULTRASOUND. */
    static final List<String> ULTRASOUND_TERMS = List.of("echo", "ultras", "ecogra", "sonogra", "doppler");

    private static final Set<String> ULTRASOUND_WORDS = Set.of("us", "usg");

    /** The abdomen and the pelvis, as a study's name or region says them. */
    static final List<String> ABDOMEN_PELVIS_TERMS = List.of("abdom", "ventre", "vientre", "belly", "hypogastr",
        "iliaque", "iliac", "lomb", "lumb", "sacr", "coccy", "hanche", "cadera", "vessie", "bladder", "vejiga",
        "urogra", "uroscan", "cysto", "renal", "kidney", "rinon");

    private static final Set<String> ABDOMEN_PELVIS_WORDS =
        Set.of("asp", "hip", "hips", "rein", "reins", "tap", "uiv");

    /** A field longer than any column that feeds this rule is cut: the read stays one bounded pass. */
    private static final int MAX_READ = 4000;

    private PregnancyRevealingStudyRule() {
    }

    /** How a study stands for a family proxy. */
    public enum Verdict {
        /** Nothing says it may reveal a pregnancy: shown, subject to its visit's own sensitivity. */
        SHOWN,
        /** Its catalogue exam is flagged, or its words name an obstetric, pelvic or gynaecological study. */
        REVEALING,
        /**
         * Nobody cleared it and the patient may be pregnant (the fail-closed band): kept from the proxy, except
         * from a reader the sensitive-category policy lets read a pregnancy row of this patient (a verified
         * parent or guardian of a child under the hospital's adolescent age, {@code ProxyRecordFilter}).
         */
        UNCLEARED
    }

    /**
     * How this order stands for a family proxy.
     *
     * @param catalogueFlag the pregnancy flag of the order's catalogue exam: TRUE flagged, FALSE cleared, null when
     *                      nobody decided or the order names no catalogue exam
     * @param patient       whose study it is; null is read as a patient who may be pregnant
     */
    public static Verdict judge(ImagingOrder order, Boolean catalogueFlag, Patient patient) {
        boolean recordedMale = patient != null && ObstetricEligibility.isRecordedMale(patient);
        return judge(order, catalogueFlag, recordedMale, ageOf(patient));
    }

    /** {@link #judge(ImagingOrder, Boolean, Patient)} with the patient's sex and age already read. */
    public static Verdict judge(ImagingOrder order, Boolean catalogueFlag, boolean recordedMale, Integer ageYears) {
        if (order == null || Boolean.TRUE.equals(catalogueFlag)) {
            return Verdict.REVEALING;
        }
        if (Boolean.FALSE.equals(catalogueFlag)) {
            // The exam is cleared; what the orderer typed about this one patient is still read.
            return namesPregnancyStudy(order.getClinicalQuestion()) ? Verdict.REVEALING : Verdict.SHOWN;
        }
        if (namesPregnancyStudy(order.getStudyType(), order.getBodyRegion(), order.getClinicalQuestion())) {
            return Verdict.REVEALING;
        }
        boolean uncleared = mayBePregnant(recordedMale, ageYears)
            && ultrasoundOrAbdominoPelvic(order.getModality(), order.getStudyType(), order.getBodyRegion());
        return uncleared ? Verdict.UNCLEARED : Verdict.SHOWN;
    }

    /**
     * How a catalogue exam stands, read on its own (an invoice line priced from it, with no order in hand): the
     * same three steps as an order, on the exam's flag, name, region and modality.
     */
    public static Verdict judgeExam(Boolean catalogueFlag, ImagingModality modality, String label,
                                    String bodyRegion, Patient patient) {
        if (catalogueFlag != null) {
            return Boolean.TRUE.equals(catalogueFlag) ? Verdict.REVEALING : Verdict.SHOWN;
        }
        if (namesPregnancyStudy(label, bodyRegion)) {
            return Verdict.REVEALING;
        }
        boolean recordedMale = patient != null && ObstetricEligibility.isRecordedMale(patient);
        boolean uncleared = mayBePregnant(recordedMale, ageOf(patient))
            && ultrasoundOrAbdominoPelvic(modality, label, bodyRegion);
        return uncleared ? Verdict.UNCLEARED : Verdict.SHOWN;
    }

    private static Integer ageOf(Patient patient) {
        return patient == null || patient.getDateOfBirth() == null ? null
            : Period.between(patient.getDateOfBirth(), LocalDate.now(ZoneId.systemDefault())).getYears();
    }

    /**
     * What a catalogue exam nobody decided is taken for, and what the catalogue screen proposes: an exam whose
     * name, region or link names an obstetric, pelvic or gynaecological study may reveal a pregnancy.
     */
    public static boolean namesPregnancyStudy(String... texts) {
        if (texts == null) {
            return false;
        }
        for (String raw : texts) {
            String text = fold(raw);
            if (text.isEmpty()) {
                continue;
            }
            if (containsAny(text, TERMS)) {
                return true;
            }
            List<String> words = wordsOf(text);
            if (namesWeeks(words) || namesObstetricWord(words, text)) {
                return true;
            }
        }
        return false;
    }

    /** The record does not say male, and the age is in the band or unknown. */
    static boolean mayBePregnant(boolean recordedMale, Integer ageYears) {
        if (recordedMale) {
            return false;
        }
        return ageYears == null || (ageYears >= FAIL_CLOSED_MIN_AGE && ageYears <= FAIL_CLOSED_MAX_AGE);
    }

    /** An ultrasound (by modality or by name), or a study of the abdomen or the pelvis. */
    static boolean ultrasoundOrAbdominoPelvic(ImagingModality modality, String... texts) {
        if (modality == ImagingModality.ULTRASOUND) {
            return true;
        }
        if (texts == null) {
            return false;
        }
        for (String raw : texts) {
            String text = fold(raw);
            if (!text.isEmpty() && namesUltrasoundOrAbdomen(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesUltrasoundOrAbdomen(String text) {
        if (containsAny(text, ULTRASOUND_TERMS) || containsAny(text, ABDOMEN_PELVIS_TERMS)) {
            return true;
        }
        for (String word : wordsOf(text)) {
            if (ULTRASOUND_WORDS.contains(word) || ABDOMEN_PELVIS_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesObstetricWord(List<String> words, String text) {
        for (String word : words) {
            boolean cervixElsewhere = "col".equals(word) && containsAny(text, NOT_A_CERVIX);
            if (WORDS.contains(word) && !cervixElsewhere) {
                return true;
            }
        }
        return false;
    }

    /** A number followed by « SA » or « SG », together (« 12sa ») or as the next word (« 12 SA », « 12,5 SA »). */
    private static boolean namesWeeks(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            int digits = leadingDigits(word);
            boolean joined = digits > 0 && digits < word.length() && WEEK_UNITS.contains(word.substring(digits));
            boolean next = digits > 0 && digits == word.length() && i + 1 < words.size()
                && WEEK_UNITS.contains(words.get(i + 1));
            if (joined || next) {
                return true;
            }
        }
        return false;
    }

    private static int leadingDigits(String word) {
        int count = 0;
        while (count < word.length() && Character.isDigit(word.charAt(count))) {
            count++;
        }
        return count;
    }

    private static boolean containsAny(String text, List<String> stems) {
        for (String stem : stems) {
            if (text.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /** The text's runs of letters and digits, in order. One pass, no regular expression. */
    private static List<String> wordsOf(String text) {
        List<String> words = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            boolean inWord = Character.isLetterOrDigit(text.charAt(i));
            if (inWord && start < 0) {
                start = i;
            } else if (!inWord && start >= 0) {
                words.add(text.substring(start, i));
                start = -1;
            }
        }
        if (start >= 0) {
            words.add(text.substring(start));
        }
        return words;
    }

    /** Lower case, accents removed, cut at {@link #MAX_READ}; empty for null. */
    private static String fold(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() > MAX_READ ? value.substring(0, MAX_READ) : value;
        String decomposed = Normalizer.normalize(bounded.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                folded.append(c);
            }
        }
        return folded.toString();
    }
}
