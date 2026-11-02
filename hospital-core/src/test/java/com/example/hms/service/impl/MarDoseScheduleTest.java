package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @ValueSource(strings = {"once", "STAT", "q4h PRN", "as needed", "after meals", "q0h", "5x daily",
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

    /**
     * QA D1237 guard: every number of hours a prescriber may write as a word,
     * in each language and form the portal is used in, reads as that number.
     * A new language, phrasing or number word the reader does not know fails
     * here instead of ending a course on the ward.
     */
    /** Number words one to twelve (and 24) in FR, ES and EN: the input space of D1237 and D1263. */
    private static final Map<Integer, List<String>> NUMBER_WORDS = Map.ofEntries(
            Map.entry(1, List.of("une", "una", "one")), Map.entry(2, List.of("deux", "dos", "two")),
            Map.entry(3, List.of("trois", "tres", "three")), Map.entry(4, List.of("quatre", "cuatro", "four")),
            Map.entry(5, List.of("cinq", "cinco", "five")), Map.entry(6, List.of("six", "seis", "six")),
            Map.entry(7, List.of("sept", "siete", "seven")), Map.entry(8, List.of("huit", "ocho", "eight")),
            Map.entry(9, List.of("neuf", "nueve", "nine")), Map.entry(10, List.of("dix", "diez", "ten")),
            Map.entry(11, List.of("onze", "once", "eleven")), Map.entry(12, List.of("douze", "doce", "twelve")),
            Map.entry(24, List.of("vingt-quatre", "veinticuatro", "twenty-four")));

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
        List<List<String>> templates = List.of(
            List.of("%s fois par jour", "%s fois/j", "%s fois par 24 h"),
            List.of("%s veces al día", "%s veces por día"),
            List.of("%s times a day", "%s times daily", "%s times per day"));
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
        assertThat(checked).isEqualTo(12 * 8 * 2);
        assertThat(wrong).isEmpty();
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

    /** QA D1237: only a frequency that says so is a single dose or as-needed. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "once|ONE_OFF", "STAT|ONE_OFF", "une seule fois|ONE_OFF", "Une fois|ONE_OFF", "dose unique|ONE_OFF",
        "una vez|ONE_OFF", "dosis única|ONE_OFF", "single dose|ONE_OFF",
        "PRN|AS_NEEDED", "si besoin|AS_NEEDED", "q4h PRN|AS_NEEDED", "cada 6 horas si es necesario|AS_NEEDED",
        "after meals|UNREAD", "après les repas|UNREAD", "une fois par semaine|UNREAD", "q0h|UNREAD",
        "24h/24|UNREAD", "2 fois|UNREAD", "toutes les 30 minutes|UNREAD"
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
