package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.Patient;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.recordaccess.ProxySensitivityPolicy;
import com.example.hms.utility.ObstetricEligibility;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
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
 *       unless what the orderer typed on that one order names a pregnancy: its
 *       clinical question, or a body region that is not the exam's own. A
 *       cleared exam counts only for an order still named as the exam is: an
 *       order placed before the exam was renamed is read as undecided.</li>
 *   <li><b>The words</b> ({@link #TERMS}, {@link #WORDS}), for an exam nobody
 *       decided and for a free-text order: obstetric, pelvic and gynaecological
 *       stems, FR/EN/ES, read without case or accents, whoever the patient is.</li>
 *   <li><b>Fail closed.</b> For a patient whose record does not say male, aged
 *       {@value #FAIL_CLOSED_MIN_AGE} to {@value #FAIL_CLOSED_MAX_AGE} (or of
 *       unknown age), every ultrasound and every abdominal or pelvic study is
 *       kept from the proxy until its catalogue exam is explicitly cleared; so
 *       is a study that does not say which part of the body it is of (no body
 *       region, and a name with none of {@link #ELSEWHERE_WORDS}). An age that
 *       cannot be believed (a birth date in the future, or more than
 *       {@value #MAX_PLAUSIBLE_AGE} years ago) is an unknown age. It
 *       is then a pregnancy row of that patient: the one reader the
 *       sensitive-category policy lets read such a row (a verified parent or
 *       guardian of a child under the hospital's adolescent age) still reads
 *       it; a flagged or a named study is read by no proxy at all.</li>
 * </ol>
 * A wrong match only hides a study from the caregiver, never shows one. What
 * the proxy would read with the study is read too, by the same words
 * ({@code PatientImagingPortalService}): a released report (its title, region,
 * technique, findings, impression, recommendations, comparison) and the
 * captions of its images; a match keeps the study from every proxy.
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

    /**
     * Body parts outside the abdomen and the pelvis, as whole words (FR/EN/ES, no accents). With no body region
     * given, a study whose name has none of them does not say where it is: for a patient who may be pregnant it is
     * held back. Bounded, as the other lists: a word missing here only hides a study.
     */
    static final Set<String> ELSEWHERE_WORDS = Set.of(
        "thorax", "thoracique", "thoracic", "chest", "torax", "poumon", "poumons", "pulmonaire", "lung", "lungs",
        "pulmon", "pulmones", "crane", "cranien", "cerebral", "cerebrale", "cerveau", "encephale", "brain", "head",
        "tete", "cabeza", "craneo", "sinus", "dentaire", "dental", "panoramique", "epaule", "epaules", "shoulder",
        "hombro", "bras", "humerus", "arm", "brazo", "coude", "elbow", "codo", "poignet", "wrist", "muneca",
        "main", "mains", "hand", "mano", "doigt", "doigts", "finger", "dedo", "genou", "genoux", "knee", "rodilla",
        "jambe", "tibia", "leg", "pierna", "cheville", "ankle", "tobillo", "pied", "pieds", "foot", "pie",
        "orteil", "clavicule", "sternum", "orbite", "orbites", "mandibule", "coeur", "cardiaque", "cardiac",
        "heart", "corazon", "sein", "seins", "mammaire", "breast", "mama", "cou", "neck", "cuello");

    /** The modalities that say the body part by themselves (the breast, the bone density sites). */
    private static final Set<ImagingModality> SELF_LOCATING =
        EnumSet.of(ImagingModality.MAMMOGRAPHY, ImagingModality.DEXA);

    /** An age above this is a placeholder date of birth (1900-01-01), not an age: it is read as unknown. */
    public static final int MAX_PLAUSIBLE_AGE = 110;

    /**
     * The longest text the rule reads (a report's findings are long). A longer one cannot be judged in a bounded
     * pass: it is taken as naming a pregnancy study, never as naming none.
     */
    private static final int MAX_READ = 100_000;

    /**
     * What the catalogue says of an order's exam: its flag (TRUE flagged, FALSE cleared, null when nobody decided),
     * and the exam's own body region, which a cleared exam's orders carry without it being something the orderer
     * typed about one patient.
     */
    public record ExamDecision(Boolean flag, String examRegion) {

        /** A decision with no region of the exam's own. */
        public static ExamDecision of(Boolean flag) {
            return new ExamDecision(flag, null);
        }
    }

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
     * @param decision what the catalogue says of the order's exam; null when the order names no catalogue exam,
     *                 when nobody decided, or when the exam was cleared under another name than the order's
     * @param patient  whose study it is; null is read as a patient who may be pregnant
     */
    public static Verdict judge(ImagingOrder order, ExamDecision decision, Patient patient) {
        boolean recordedMale = patient != null && ObstetricEligibility.isRecordedMale(patient);
        return verdict(order, decision, recordedMale, ageOf(patient));
    }

    /** The verdict with the exam's flag alone, and the patient's sex and age already read. */
    public static Verdict judge(ImagingOrder order, Boolean catalogueFlag, boolean recordedMale, Integer ageYears) {
        return verdict(order, ExamDecision.of(catalogueFlag), recordedMale, ageYears);
    }

    private static Verdict verdict(ImagingOrder order, ExamDecision decision, boolean recordedMale,
                                   Integer ageYears) {
        Boolean flag = decision == null ? null : decision.flag();
        String examRegion = decision == null ? null : decision.examRegion();
        if (order == null || Boolean.TRUE.equals(flag)) {
            return Verdict.REVEALING;
        }
        // Not flagged (answered above): a decision that is there is a clearance.
        boolean cleared = flag != null;
        // What the orderer typed about this one patient is read whatever the exam says: the question, and a
        // body region that is not the cleared exam's own.
        boolean regionIsTheExams = cleared && sameText(order.getBodyRegion(), examRegion);
        if (namesPregnancyStudy(order.getClinicalQuestion())
                || (!regionIsTheExams && namesPregnancyStudy(order.getBodyRegion()))) {
            return Verdict.REVEALING;
        }
        if (cleared) {
            return Verdict.SHOWN;
        }
        if (namesPregnancyStudy(order.getStudyType())) {
            return Verdict.REVEALING;
        }
        boolean uncleared = mayBePregnant(recordedMale, ageYears)
            && (ultrasoundOrAbdominoPelvic(order.getModality(), order.getStudyType(), order.getBodyRegion())
                || bodyPartUnknown(order.getModality(), order.getStudyType(), order.getBodyRegion()));
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
            && (ultrasoundOrAbdominoPelvic(modality, label, bodyRegion)
                || bodyPartUnknown(modality, label, bodyRegion));
        return uncleared ? Verdict.UNCLEARED : Verdict.SHOWN;
    }

    /**
     * The patient's age in whole years, or null when it is not known: no birth date, one in the future, or one
     * more than {@link #MAX_PLAUSIBLE_AGE} years ago (the 1900-01-01 placeholder). Unknown is inside the band.
     */
    static Integer ageOf(Patient patient) {
        Integer age = patient == null ? null
            : ProxySensitivityPolicy.ageOn(patient.getDateOfBirth(), LocalDate.now(ZoneId.systemDefault()));
        return age != null && age > MAX_PLAUSIBLE_AGE ? null : age;
    }

    /**
     * Whether the study does not say which part of the body it is of: no body region, a modality that does not
     * say it either, and a name with none of {@link #ELSEWHERE_WORDS} (« IRM », « Scanner », « Bilan »).
     */
    static boolean bodyPartUnknown(ImagingModality modality, String studyType, String bodyRegion) {
        if (!fold(bodyRegion).isBlank() || SELF_LOCATING.contains(modality)) {
            return false;
        }
        for (String word : wordsOf(fold(studyType))) {
            if (ELSEWHERE_WORDS.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** The same text, case, accents and outer spaces aside; never true for a blank one. */
    private static boolean sameText(String one, String other) {
        String left = fold(one).trim();
        return !left.isEmpty() && left.equals(fold(other).trim());
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
            if (raw != null && raw.length() > MAX_READ) {
                // Too long to judge in a bounded pass: never taken as naming nothing.
                return true;
            }
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
