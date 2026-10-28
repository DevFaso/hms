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
}
