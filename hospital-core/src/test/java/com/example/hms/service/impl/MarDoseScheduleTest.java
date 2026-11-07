package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** D77: a frequency becomes a dosing interval only when it is an unambiguous repeat. */
class MarDoseScheduleTest {

    @ParameterizedTest
    @CsvSource({
        "q12h, 12", "Q 8 H, 8", "q6h, 6", "BID, 12", "b.i.d., 12", "TID, 8", "QID, 6",
        "daily, 24", "QD, 24", "every 4 hours, 4", "2x/j, 12", "3 fois par jour, 8"
    })
    void readsRepeatingFrequencies(String frequency, int hours) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isEqualTo(hours);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"once", "STAT", "q4h PRN", "as needed", "q0h", "5x daily",
        "une seule fois", "si besoin, une fois par jour", "Toutes les 4 heures (si besoin)",
        "Si besoin (PRN)", "cada 6 horas si es necesario"})
    void anythingElseIsASingleDose(String frequency) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isNull();
    }

    /**
     * QA D928 replay (k4.js/u16.js): the frequencies the testers typed, and the
     * other ways French and Spanish write them. "Une fois par jour" used to be
     * no repeat at all, so the first held dose ended a five-day course.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Une fois par jour|24", "Deux fois par jour|12", "Trois fois par jour|8", "quatre fois par jour|6",
        "1 fois par jour|24", "Matin et soir|12", "matin, midi et soir|8", "tous les jours|24",
        "Toutes les 6 heures|6", "una vez al día|24", "dos veces al día|12", "Tres veces al día|8",
        "cada 8 horas|8", "twice a day|12", "three times a day|8", "once daily|24"
    })
    void d928_readsWordFrequencies(String frequency, int hours) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isEqualTo(hours);
    }

    /**
     * QA D928 guard: every frequency label the portal offers, in every locale,
     * reads as the interval of its code. ORDER_SETS.FREQUENCY is the one
     * vocabulary of frequencies the portal writes; a new label (or a new
     * language) that the MAR cannot schedule fails here instead of silently
     * ending a course after its first dose.
     */
    @Test
    void d928_everyPortalFrequencyLabelReadsAsItsCode() throws Exception {
        Map<String, Integer> expected = Map.of("QD", 24, "BID", 12, "TID", 8, "QID", 6,
            "Q4H", 4, "Q6H", 6, "Q8H", 8);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String locale : List.of("en", "fr", "es")) {
            JsonNode labels = new ObjectMapper()
                .readTree(Files.readString(Path.of("..", "hospital-portal", "src", "assets", "i18n", locale + ".json")))
                .path("ORDER_SETS").path("FREQUENCY");
            Iterator<Map.Entry<String, JsonNode>> it = labels.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Integer want = expected.get(e.getKey());
                Integer got = MarDoseSchedule.intervalHours(e.getValue().asText());
                Integer gotForCode = MarDoseSchedule.intervalHours(e.getKey());
                checked++;
                if (!java.util.Objects.equals(want, got) || !java.util.Objects.equals(want, gotForCode)) {
                    wrong.add(locale + " " + e.getKey() + " \"" + e.getValue().asText() + "\" -> " + got);
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(24);
        assertThat(wrong).isEmpty();
    }

    /**
     * QA D1237 replay (freqmar.js): frequencies written in words. "toutes les
     * six heures", "toutes les heures" and "cada hora" used to read as no
     * repeat, so the MAR ended the course after the first dose.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "toutes les six heures|6", "toutes les heures|1", "Toutes les deux heures|2", "toutes les 6 heure|6",
        "toutes les huit heures|8", "toutes les douze heures|12", "toutes les vingt-quatre heures|24",
        "chaque heure|1", "cada hora|1", "cada seis horas|6", "cada ocho horas|8", "cada once horas|11",
        "every hour|1", "every six hours|6", "every twelve hours|12", "hourly|1", "Every 1 hour|1",
        "6h/6h|6", "6 h / 6 h|6", "8/8h|8", "12h/12|12", "tous les 2 jours|48", "every other day|48",
        "Toutes les six heures pendant 2 jours|6", "toutes les 6 heures (Q6H)|6", "Cada 8 horas (Q8H)|8"
    })
    void d1237_readsHourFrequenciesWrittenInWords(String frequency, int hours) {
        assertThat(MarDoseSchedule.intervalHours(frequency)).isEqualTo(hours);
        assertThat(MarDoseSchedule.read(frequency).kind()).isEqualTo(MarDoseSchedule.Kind.REPEATING);
    }

    /** Number words one to twelve (and 24) in FR, ES and EN: the input space of D1237 and D1263. */
    private static final Map<Integer, List<String>> NUMBER_WORDS = Map.ofEntries(
            Map.entry(1, List.of("une", "una", "one")), Map.entry(2, List.of("deux", "dos", "two")),
            Map.entry(3, List.of("trois", "tres", "three")), Map.entry(4, List.of("quatre", "cuatro", "four")),
            Map.entry(5, List.of("cinq", "cinco", "five")), Map.entry(6, List.of("six", "seis", "six")),
            Map.entry(7, List.of("sept", "siete", "seven")), Map.entry(8, List.of("huit", "ocho", "eight")),
            Map.entry(9, List.of("neuf", "nueve", "nine")), Map.entry(10, List.of("dix", "diez", "ten")),
            Map.entry(11, List.of("onze", "once", "eleven")), Map.entry(12, List.of("douze", "doce", "twelve")),
            Map.entry(24, List.of("vingt-quatre", "veinticuatro", "twenty-four")));

    /**
     * QA D1237 guard: every number of hours a prescriber may write as a word,
     * in each language and form the portal is used in, reads as that number.
     * A new language, phrasing or number word the reader does not know fails
     * here instead of ending a course on the ward.
     */
    @Test
    void d1237_everyHourWordInEveryLanguageReadsAsItsNumber() {
        Map<Integer, List<String>> words = NUMBER_WORDS;
        List<String> templates = List.of("toutes les %s heures", "cada %s horas", "every %s hours");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<Integer, List<String>> e : words.entrySet()) {
            for (int lang = 0; lang < templates.size(); lang++) {
                String text = String.format(templates.get(lang), e.getValue().get(lang));
                checked++;
                if (!java.util.Objects.equals(MarDoseSchedule.intervalHours(text), e.getKey())) {
                    wrong.add(text + " -> " + MarDoseSchedule.intervalHours(text));
                }
            }
        }
        assertThat(checked).isEqualTo(39);
        assertThat(wrong).isEmpty();
    }

    /**
     * QA D1263 (round 26) guard: EVERY count of doses a day, one to twelve, as
     * a word in FR, ES and EN and as digits, in each way the portal's users
     * write "per day", reads as that many doses (an interval of 24 h / n).
     * Round 25 read "5 fois par jour" but not "cinq fois par jour": the word
     * list for counts was shorter than the one for hours. Both now come from
     * {@link #NUMBER_WORDS}, so a word missing from either reader fails here.
     */
    @Test
    void d1263_everyCountOfDosesADayInEveryLanguageAndFormIsRead() {
        // QA D1291 (round 27): "2 fois/jour", "deux fois/jour", "/día", "/day" were not read.
        List<List<String>> templates = List.of(
            List.of("%s fois par jour", "%s fois/j", "%s fois par 24 h", "%s fois/jour", "%s fois / jour"),
            List.of("%s veces al día", "%s veces por día", "%s veces/día", "%s veces/dia"),
            List.of("%s times a day", "%s times daily", "%s times per day", "%s times/day"));
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int n = 1; n <= 12; n++) {
            for (int lang = 0; lang < templates.size(); lang++) {
                for (String template : templates.get(lang)) {
                    for (String count : List.of(NUMBER_WORDS.get(n).get(lang), String.valueOf(n))) {
                        String text = String.format(template, count);
                        checked++;
                        MarDoseSchedule.Reading reading = MarDoseSchedule.read(text);
                        if (!reading.repeating() || reading.intervalMinutes() != 24 * 60 / n
                            || !java.util.Objects.equals(MarDoseSchedule.dosesPerDay(text), (double) n)) {
                            wrong.add(text + " -> " + reading);
                        }
                    }
                }
            }
        }
        // 13 forms (was 8 before QA D1291), each with the word and the digits, for 1 to 12.
        assertThat(checked).isEqualTo(12 * 13 * 2);
        assertThat(wrong).isEmpty();
    }

    /**
     * QA D1291 (round 27, user decision): "2x/j" and its relatives read as N a
     * day, and a posology "1-0-1" ("1-1-1", "1-0-0-1", ...) as the count of its
     * non-zero intakes a day; anything else stays unread.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2 fois/jour|720", "deux fois/jour|720", "2 fois/j|720", "2x/j|720", "3x/j|480", "2x/jour|720",
        "2 X / JOUR|720", "2 veces/día|720", "dos veces/dia|720", "2 times/day|720", "3 x/day|480",
        "1-0-1|720", "1-1-1|480", "1-0-0-1|720", "1-1-1-1|360", "0-0-1|1440", "2-0-2|720", "1 - 0 - 1|720"
    })
    void d1291_shorthandAndPosologyAreRead(String frequency, int minutes) {
        MarDoseSchedule.Reading reading = MarDoseSchedule.read(frequency);
        assertThat(reading.kind()).as(frequency).isEqualTo(MarDoseSchedule.Kind.REPEATING);
        assertThat(reading.intervalMinutes()).as(frequency).isEqualTo(minutes);
        assertThat(MarDoseSchedule.dosesPerDay(frequency)).as(frequency).isEqualTo(1440.0 / minutes);
    }

    /**
     * QA D1308 (round 28; D1291 NOT_FIXED): the D1263 / D1291 templates all
     * put a word ("fois", "x", "times") between the count and the per-day
     * slash, so the bare shorthand "2/j" - the usual Burkinabe way - was never
     * tried and stayed unread. Every count one to twelve, written bare before
     * every per-day slash in FR, ES and EN, with and without spaces, reads as
     * that many doses a day; and "N x/j", "N x/día", "N x/day" too.
     */
    @Test
    void d1308_everyBareCountBeforeAPerDaySlashIsRead() {
        List<String> templates = List.of(
            "%s/j", "%s/jour", "%s/jours", "%s / j", "%s /jour", "%s x/j", "%s x/jour",
            "%s/día", "%s/dia", "%s/días", "%s x/día",
            "%s/day", "%s/days", "%s/d", "%s x/day");
        List<String> wrong = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            for (String template : templates) {
                String text = String.format(template, n);
                MarDoseSchedule.Reading reading = MarDoseSchedule.read(text);
                if (!reading.repeating() || reading.intervalMinutes() != 24 * 60 / n
                    || !java.util.Objects.equals(MarDoseSchedule.dosesPerDay(text), (double) n)) {
                    wrong.add(text + " -> " + reading);
                }
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(MarDoseSchedule.read("2/j").intervalMinutes()).isEqualTo(720);
        assertThat(MarDoseSchedule.read("3/j").intervalMinutes()).isEqualTo(480);
    }

    /**
     * QA D1308: a bare count that is not a day's doses stays unread (nothing
     * guessed). QA D1426 (changed): "2/semaine" and "500 mg/j" state a cadence,
     * "24/24" an infusion: {@link #d1426_aCadenceAnywhereIsRead}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0/j", "25/j", "1/2 j"})
    void d1308_aBareCountThatIsNotADaysDosesStaysUnread(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).as(frequency).isEqualTo(MarDoseSchedule.Kind.UNREAD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0-0-0", "1-0", "1-0-1-0-1", "10-0-10", "1-a-1", "/jour"})
    void d1291_anythingElseStaysUnread(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).as(frequency).isEqualTo(MarDoseSchedule.Kind.UNREAD);
    }

    /** QA D1263: "5 fois par jour" is still every 4 h 48, and a count over hourly is not a MAR schedule. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"5 fois par jour|288", "cinq fois par jour|288", "cinco veces al día|288",
        "five times a day|288", "huit fois par jour|180", "eight times a day|180", "24 fois par jour|60"})
    void d1263_countsReadAsTheirInterval(String frequency, int minutes) {
        assertThat(MarDoseSchedule.read(frequency).intervalMinutes()).isEqualTo(minutes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"25 fois par jour", "48 times a day", "100 fois par jour"})
    void d1263_aCountOverHourlyIsNotASchedule(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isEqualTo(MarDoseSchedule.Kind.UNREAD);
    }

    /** The shared as-needed fixture (QA D1360 / D1378; PrnAsNeededBackfillMigrationIT reads it too). */
    static final Path AS_NEEDED_CASES =
        Path.of("..", "hospital-portal", "src", "app", "shared", "as-needed-frequency.cases.json");

    static List<String> asNeededCases() throws Exception {
        return fixtureList("asNeeded");
    }

    static List<String> notAsNeededCases() throws Exception {
        return fixtureList("notAsNeeded");
    }

    static List<String> conditionalCases() throws Exception {
        return fixtureList("conditional");
    }

    static List<String> fixedScheduleCases() throws Exception {
        return fixtureList("fixedSchedule");
    }

    static List<String> fixtureList(String key) throws Exception {
        List<String> out = new ArrayList<>();
        new ObjectMapper().readTree(Files.readString(AS_NEEDED_CASES)).path(key).forEach(n -> out.add(n.asText()));
        assertThat(out).as("fixture list " + key).isNotEmpty();
        return out;
    }

    /**
     * QA D1360 replay (prn31.js, prnword.js), QA D1378 (round 32): the frozen
     * round-31 phrases are never a schedule (no repeat, no doses per day), and
     * the text alone never makes an order as-needed any more: on an order the
     * prescriber did not tick, the MAR keeps the course open and flagged
     * (UNREAD); ticked, it is AS_NEEDED whatever the words.
     */
    @ParameterizedTest
    @MethodSource("asNeededCases")
    void d1378_aRound31AsNeededWordingIsNeverASchedule(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isEqualTo(MarDoseSchedule.Kind.UNREAD);
        assertThat(MarDoseSchedule.intervalHours(frequency)).isNull();
        assertThat(MarDoseSchedule.dosesPerDay(frequency)).isNull();
        com.example.hms.model.Prescription ticked = new com.example.hms.model.Prescription();
        ticked.setFrequency(frequency);
        ticked.setAsNeeded(true);
        assertThat(MarDoseSchedule.read(ticked).kind()).isEqualTo(MarDoseSchedule.Kind.AS_NEEDED);
        ticked.setAsNeeded(false);
        assertThat(MarDoseSchedule.read(ticked).kind()).isEqualTo(MarDoseSchedule.Kind.UNREAD);
    }

    @ParameterizedTest
    @MethodSource("notAsNeededCases")
    void d1360_theSharedNonAsNeededCasesAreNot(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isNotEqualTo(MarDoseSchedule.Kind.AS_NEEDED);
    }

    /**
     * QA D1378 (round 32): NO frequency text is read as AS_NEEDED, however it
     * is written; only the prescriber's tick is (MarAsNeededFlagGuardTest
     * keeps every reader on the tick).
     */
    @ParameterizedTest
    @MethodSource({"asNeededCases", "notAsNeededCases", "conditionalCases", "fixedScheduleCases"})
    void d1378_theTextAloneIsNeverAsNeeded(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isNotEqualTo(MarDoseSchedule.Kind.AS_NEEDED);
    }

    /**
     * QA D1378 replay (prn32*.js, prnw32.js, prnsweep32.log, rxprn32.js): the
     * wording rule refuses every round-31 and round-32 as-needed wording - SOS,
     * p.r.n., sur demande, si precisa, s/p, when necessary, for pain, si EVA > 4
     * ... - and every frozen round-31 phrase case (QA D1391 / D1395: as reading
     * "as needed").
     */
    @ParameterizedTest
    @MethodSource({"conditionalCases", "asNeededCases"})
    void d1378_theSafetyNetCatchesEveryAsNeededWording(String frequency) {
        assertThat(FrequencyWording.classify(frequency)).as(frequency)
            .isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
    }

    /** QA D1378: a fixed schedule, a single dose or nothing written is never asked to confirm. */
    @ParameterizedTest
    @MethodSource("fixedScheduleCases")
    @NullAndEmptySource
    void d1378_theSafetyNetLeavesAFixedScheduleAlone(String frequency) {
        assertThat(FrequencyWording.classify(frequency)).as(frequency).isEqualTo(FrequencyWording.Verdict.FIXED);
    }

    /** QA D1378: the fixture holds every wording QA filed under D1378 and D1360, word for word. */
    @Test
    void d1378_theFixtureHoldsEveryFiledWording() throws Exception {
        List<String> conditional = conditionalCases();
        assertThat(conditional).contains(
            "au besoin", "à la demande", "si nécessaire", "si douleur",
            "SOS", "en SOS", "si SOS", "S.O.S.", "p.r.n.", "P.R.N", "P.R.N.", "pro re nata", "sur demande",
            "s'il y a besoin", "si nécessité", "en cas de nécessité", "selon nécessité", "quand nécessaire",
            "si mal", "si nausées", "si vomissements", "si agitation", "si insomnie", "si céphalées", "si crise",
            "en cas de crise", "selon douleur", "s'il y a douleur", "si EVA > 4", "si T° > 38,5",
            "si température > 38",
            "si precisa", "s/p", "según sea necesario", "cuando sea necesario", "cuando se necesite",
            "cuando lo necesite", "en caso de necesidad", "a necesidad", "si fuera necesario", "si hay dolor",
            "si náuseas",
            "when necessary", "as necessary", "for pain", "if nauseous", "if nausea", "if nauseated");
    }

    /** QA D1360 guard: every phrase of the vocabulary has a case in the shared fixture, in its own words. */
    @Test
    void d1360_everyAsNeededPhraseIsInTheSharedFixture() throws Exception {
        String cases = String.join("|", asNeededCases()).toUpperCase(Locale.ROOT);
        String folded = Normalizer.normalize(cases, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        assertThat(MarDoseSchedule.AS_NEEDED_PHRASES).hasSizeGreaterThanOrEqualTo(18)
            .allSatisfy(phrase -> assertThat(folded).as(phrase).contains(phrase));
    }

    /** QA D1237: only a frequency that says so is a single dose (QA D1378: as-needed is the tick). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "once|ONE_OFF", "STAT|ONE_OFF", "une seule fois|ONE_OFF", "Une fois|ONE_OFF", "dose unique|ONE_OFF",
        "una vez|ONE_OFF", "dosis única|ONE_OFF", "single dose|ONE_OFF",
        // QA D1378: the text alone is never AS_NEEDED; an unticked as-needed wording stays open (UNREAD).
        "PRN|UNREAD", "si besoin|UNREAD", "q4h PRN|UNREAD", "cada 6 horas si es necesario|UNREAD",
        // QA D1426 (changed): meals, a week and thirty minutes state a cadence, 24h/24 an infusion.
        "after meals|REPEATING", "après les repas|REPEATING", "une fois par semaine|REPEATING", "q0h|UNREAD",
        "24h/24|CONTINUOUS", "2 fois|UNREAD", "toutes les 30 minutes|REPEATING"
    })
    void d1237_readsWhatTheFrequencySays(String frequency, MarDoseSchedule.Kind kind) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isEqualTo(kind);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void d1237_noFrequencyWrittenIsAOneOffOrder(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).isEqualTo(MarDoseSchedule.Kind.ONE_OFF);
    }

    /** QA D1426: a cadence is read wherever it stands in the sentence, and what the fixture does not hold reads too. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2/semaine|5040", "2 fois/semaine|5040", "500 mg/j|1440", "q30min|30", "q2d|2880", "tous les mois|43200",
        "cada mes|43200", "every week|10080", "8/8|480", "x 3|480", "3f/j|480", "at bedtime|1440", "matin et soir|720",
        "take 1 tablet twice daily|720", "tomar 1 cada 8 horas|480", "08:00 14:00 20:00|480", "with food|480"})
    void d1426_aCadenceAnywhereIsRead(String frequency, int minutes) {
        assertThat(MarDoseSchedule.read(frequency).intervalMinutes()).as(frequency).isEqualTo(minutes);
    }

    /** QA D1426: a single-dose form ends after its dose, whatever stands beside it. */
    @ParameterizedTest
    @ValueSource(strings = {"Prise unique (TPI-SP 2)", "prise unique à la CPN", "en une prise", "SP dose unique", "ce soir",
        "1 fois ce soir", "à la naissance", "avant l'intervention", "bolus", "en IVD lente", "dose de charge"})
    void d1426_aSingleDoseFormIsOneDose(String frequency) {
        assertThat(MarDoseSchedule.read(frequency).kind()).as(frequency).isEqualTo(MarDoseSchedule.Kind.ONE_OFF);
    }

    /** QA D1419: a ceiling, a range or a choice is not a schedule, but the daily limit still judges its highest count. */
    @Test
    void d1419_aCeilingIsNotAScheduleButItsCountStillMeetsTheDailyLimit() {
        for (String ceiling : List.of("max 4/j", "1 à 3 fois par jour", "jusqu'à 4 fois par jour", "up to 4 times a day")) {
            assertThat(MarDoseSchedule.read(ceiling).kind()).as(ceiling).isEqualTo(MarDoseSchedule.Kind.UNREAD);
        }
        assertThat(MarDoseSchedule.dosesPerDay("max 4/j")).isEqualTo(4d);
        assertThat(MarDoseSchedule.dosesPerDay("1 à 3 fois par jour")).isEqualTo(3d);
        assertThat(MarDoseSchedule.read("une fois par jour jusqu'à l'accouchement").intervalMinutes()).isEqualTo(1440);
    }

    /** QA D1419: the artemether-lumefantrine timing is six doses at their own moments; the daily limit counts the fullest day. */
    @Test
    void d1419_aCourseHasItsOwnMoments() {
        MarDoseSchedule.Reading reading = MarDoseSchedule.read("H0 H8 H24 H36 H48 H60");
        assertThat(reading.kind()).isEqualTo(MarDoseSchedule.Kind.COURSE);
        assertThat(reading.courseOffsets()).containsExactly(0, 480, 1440, 2160, 2880, 3600);
        assertThat(reading.gapAfter(1)).isEqualTo(480);
        assertThat(reading.gapAfter(2)).isEqualTo(960);
        assertThat(reading.gapAfter(6)).isNull();
        assertThat(MarDoseSchedule.dosesPerDay("H0 H8 H24 H36 H48 H60")).isEqualTo(2d);
        assertThat(MarDoseSchedule.read("J1 à J3").courseOffsets()).containsExactly(0, 1440, 2880);
        assertThat(MarDoseSchedule.intervalHours("J1 J2 J3")).as("not a whole-hour regimen").isNull();
    }

    /** QA D1237: "5 fois par jour" repeats (every 4 h 48), though not in whole hours. */
    @Test
    void d1237_aCountThatDoesNotDivideTheDayStillRepeats() {
        MarDoseSchedule.Reading reading = MarDoseSchedule.read("5 fois par jour");
        assertThat(reading.kind()).isEqualTo(MarDoseSchedule.Kind.REPEATING);
        assertThat(reading.intervalMinutes()).isEqualTo(288);
        assertThat(MarDoseSchedule.intervalHours("5 fois par jour")).isNull();
        assertThat(MarDoseSchedule.dosesPerDay("5 fois par jour")).isEqualTo(5d);
        assertThat(MarDoseSchedule.dosesPerDay("toutes les six heures")).isEqualTo(4d);
    }
}
