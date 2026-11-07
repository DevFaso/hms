package com.example.hms.service.impl;

import com.example.hms.enums.MedicationAdministrationStatus;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Prescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1391 / D1395 (round 33) and QA D1419 / D1426 / D1434 (round 34): the three
 * outcomes of a frequency not ticked as needed, from one reader. Every case comes
 * from the fixture the portal's mirror reads too
 * ({@code as-needed-frequency.cases.json}), which holds every fixed-schedule
 * set of the QA scripts, every filed wording, the dropdown, and the frequencies
 * of the repo's seeds and tests. The guard of the class is
 * {@link #aWordingAcceptedAsAScheduleIsNeverUnreadOnTheMar}: the accepted-schedule
 * reader and the MAR cannot disagree, because this fixture drives both.
 */
class FrequencyWordingTest {

    private static final Path MIRROR =
        Path.of("..", "hospital-portal", "src", "app", "shared", "as-needed-frequency.ts");

    static List<String> fixedCases() throws IOException {
        return fixture("fixedSchedule");
    }

    static List<String> conditionalCases() throws IOException {
        return fixture("conditional");
    }

    static List<String> unreadableCases() throws IOException {
        return fixture("unreadable");
    }

    static List<String> dropdownCases() throws IOException {
        return fixture("dropdown");
    }

    private static List<String> fixture(String key) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(MarDoseScheduleTest.AS_NEEDED_CASES));
        List<String> out = new ArrayList<>();
        root.get(key).forEach(node -> out.add(node.asText()));
        assertThat(out).as(key).isNotEmpty();
        return out;
    }

    @ParameterizedTest
    @MethodSource("fixedCases")
    void aFixedScheduleIsAccepted(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.FIXED);
    }

    @ParameterizedTest
    @MethodSource("dropdownCases")
    void aChoiceOfThePortalDropdownIsNeverRefused(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.FIXED);
        assertThat(PrnMinInterval.safetyNet(wording)).as(wording).isNull();
    }

    @ParameterizedTest
    @MethodSource("conditionalCases")
    void aConditionalWordingIsRefusedAsReadingAsNeeded(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
        assertThat(PrnMinInterval.safetyNet(wording))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.CONFIRM_KEY));
    }

    @ParameterizedTest
    @MethodSource("unreadableCases")
    void aWordingThatStatesNoScheduleIsAQuestionNotARefusal(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
        assertThat(PrnMinInterval.safetyNet(wording))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.CONFIRMED_FIELD, PrnMinInterval.UNREADABLE_KEY));
        assertThat(PrnMinInterval.safetyNet(wording, true)).as("confirmed").isNull();
        assertThat(MarDoseSchedule.read(wording).kind()).as(wording).isEqualTo(MarDoseSchedule.Kind.UNREAD);
    }

    /** A condition is not answered by confirming it: only the tick and the interval answer it (D1395). */
    @ParameterizedTest
    @MethodSource("conditionalCases")
    void aConfirmationDoesNotPassACondition(String wording) {
        assertThat(PrnMinInterval.safetyNet(wording, true))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.CONFIRM_KEY));
    }

    static List<String> scheduleWordings() throws IOException {
        return fixedCases().stream().filter(wording -> !wording.replace('\u00A0', ' ').isBlank()).toList();
    }

    /** The exact reading of every wording accepted: a repeat's minutes, a course's moments, a single dose, an infusion. */
    @ParameterizedTest
    @MethodSource("scheduleWordings")
    void everyScheduleReadsAsTheFixtureSays(String wording) throws IOException {
        JsonNode readings = new ObjectMapper().readTree(Files.readString(MarDoseScheduleTest.AS_NEEDED_CASES))
            .get("readings");
        assertThat(readings.has(wording)).as("the fixture states how \"%s\" is read", wording).isTrue();
        MarDoseSchedule.Reading reading = MarDoseSchedule.read(wording);
        String read = reading.kind()
            + (reading.intervalMinutes() != null ? ":" + reading.intervalMinutes() : "")
            + (reading.courseOffsets().isEmpty() ? "" : ":" + reading.courseOffsets().toString().replace(" ", ""));
        assertThat(read).as(wording).isEqualTo(readings.get(wording).asText());
    }

    /**
     * THE GUARD (QA D1419 + D1426, one class): the wording rule and the MAR
     * cannot disagree. A wording accepted as a schedule is never
     * {@code frequencyUnread} after its first dose, whatever it says; a wording
     * asked about always is, and every later dose needs a reason.
     */
    @ParameterizedTest
    @MethodSource("scheduleWordings")
    void aWordingAcceptedAsAScheduleIsNeverUnreadOnTheMar(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.FIXED);
        assertThat(dueAfterOneDose(wording).frequencyUnread()).as(wording).isFalse();
    }

    @ParameterizedTest
    @MethodSource("unreadableCases")
    void aWordingConfirmedIsUnreadOnTheMar(String wording) {
        assertThat(dueAfterOneDose(wording).frequencyUnread()).as(wording).isTrue();
    }

    private static MarNextDose.Due dueAfterOneDose(String wording) {
        LocalDateTime start = LocalDateTime.of(2026, 11, 6, 8, 0);
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setFrequency(wording);
        rx.setSignedAt(start);
        MedicationAdministrationRecord dose = new MedicationAdministrationRecord();
        dose.setId(UUID.randomUUID());
        dose.setStatus(MedicationAdministrationStatus.GIVEN);
        dose.setScheduledTime(start);
        dose.setAdministeredAt(start);
        return MarNextDose.of(rx, start.plusMinutes(5), List.of(dose), NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW);
    }

    /** Every wording filed under D1419 and D1426 has its one outcome in the fixture, and no wording has two. */
    @Test
    void theFixtureGivesEveryFiledWordingOneOutcome() throws IOException {
        List<String> fixed = fixedCases();
        List<String> conditional = conditionalCases();
        List<String> unreadable = unreadableCases();
        JsonNode filed = new ObjectMapper().readTree(Files.readString(MarDoseScheduleTest.AS_NEEDED_CASES)).get("filed");
        int counted = 0;
        for (String defect : List.of("D1419", "D1426")) {
            for (JsonNode node : filed.get(defect)) {
                String wording = node.asText();
                long outcomes = java.util.stream.Stream.of(fixed, conditional, unreadable)
                    .filter(list -> list.contains(wording)).count();
                assertThat(outcomes).as(defect + " " + wording).isEqualTo(1);
                counted++;
            }
        }
        assertThat(counted).as("the filed wordings").isGreaterThan(200);
        assertThat(fixed).doesNotContainAnyElementsOf(conditional).doesNotContainAnyElementsOf(unreadable);
        assertThat(conditional).doesNotContainAnyElementsOf(unreadable);
    }

    /**
     * The independent review of round 34: the rest of the sentence has its say. Each of these was read as a
     * dangerously shorter interval or as one dose ending a course; each is now read as written or asked about.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1 g x 7", "1 cp x 14", "1x10", "1 x 5 ml", "x 6 cures", "4 cp x 6", "0,5 mg sur 8 h",
        "1 prise pendant 5 jours", "4 cp matin et soir J1 à J3", "matin et soir 3 jours de suite", "H0 à H24", "H1-H6",
        "à partir de demain matin et soir", "ce soir puis tous les soirs", "tonight then every night",
        "pendant 5 jours après l'intervention", "48 h après chirurgie", "5 jours post-partum", "dès l'admission",
        "1 g IVD pendant 5 jours", "1 dose pendant 5 jours", "dose de charge puis entretien", "le 12/12",
        "du 12/12 au 15/12", "jusqu'au 8/8", "à partir du 6/6", "en dehors des repas", "à distance des repas",
        "continue", "uso continuo", "1 cp uso continuo", "traitement continu", "toutes les 4 à 6h", "cada 6 a 8h",
        "1 cp du lundi au vendredi", "Monday to Friday", "once or twice a day", "twice or three times a day", "2-3/24h",
        "1 goutte OD", "1 cp à J 3"})
    void reviewOfRound34_whatIsNotCertainIsAsked(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
    }

    /** The same review: a symptom or a measured value beside a cadence is a condition, not a schedule. */
    @ParameterizedTest
    @ValueSource(strings = {"toutes les 6 h pour hyperthermie", "q4h for anxiety", "toutes les 8 h pour diarrhée",
        "3 fois par jour selon glycémie", "10 UI selon dextro matin et soir", "toutes les 6h selon TA"})
    void reviewOfRound34_aMeasuredConditionIsNotASchedule(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
    }

    /** The same review: a regular course is not told it reads as needed for a name that holds a condition word. */
    @ParameterizedTest
    @ValueSource(strings = {"3 fois par jour pour fièvre typhoïde", "1 cp à répéter à J8", "before sleep", "avec du pain"})
    void reviewOfRound34_aNameIsNotACondition(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isNotEqualTo(FrequencyWording.Verdict.CONDITIONAL);
    }

    /** D1395, the re-test gate: its wordings are refused on the tick with the "reads as needed" message. */
    @ParameterizedTest
    @ValueSource(strings = {
        "pour douleur", "pour la fièvre", "contre la douleur", "à chaque crise", "lors des crises", "sibesoin", "SB",
        "ad lib", "ad libitum", "à volonté", "au coup par coup", "à répéter", "occasionnellement", "ponctuellement",
        "par intermittence", "para el dolor", "por fiebre", "a requerimiento", "a discreción", "in case of pain",
        "should pain occur", "as req", "as directed", "pr n", "pain", "fever", "toutes les 6 h pour douleur", "SOS",
        "for pain", "si douleur"})
    void d1395_theFiledConditionsAreRefusedOnTheTick(String wording) {
        assertThat(PrnMinInterval.safetyNet(wording))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.CONFIRM_KEY));
    }

    /** The two directions round 33 filed, named here so a fixture edit cannot quietly drop them. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Prise unique (TPI-SP 2)", "TPI-SP", "SP dose unique", "TPI SP", "SP-AQ 1 fois par jour",
        "SMC : SP + AQ, 1 fois par mois", "Prise unique à la CPN (SP)", "4 cp 2 fois par jour (poids > 35 kg)",
        "2 fois par jour selon poids", "3 fois par jour selon ordonnance", "2 fois par jour selon protocole"})
    void d1391_theFiledFixedSchedulesPass(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.FIXED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "toutes les 6 h pour douleur", "q6h si fièvre", "pour douleur", "para el dolor", "in case of pain",
        "max 4/j", "jusqu'à 4 fois par jour", "1 à 3 fois par jour", "up to 4 times a day", "douleur", "pain",
        "selon prescription médicale", "SP + AQ", "ad lib", "S/P", "s/p"})
    void d1395_noFiledWordingIsTakenAsASchedule(String wording) {
        assertThat(FrequencyWording.classify(wording)).as(wording).isNotEqualTo(FrequencyWording.Verdict.FIXED);
    }

    /** A weight band is not a condition, unless a condition stands in its brackets. */
    @Test
    void aConditionInsideAWeightBandIsStillAConditionalClause() {
        assertThat(FrequencyWording.classify("2 fois par jour, 3 jours (si poids 12 kg)"))
            .isEqualTo(FrequencyWording.Verdict.FIXED);
        assertThat(FrequencyWording.classify("1 cp 2 fois par jour (5-15 kg)")).isEqualTo(FrequencyWording.Verdict.FIXED);
        assertThat(FrequencyWording.classify("3 fois par jour (poids > 35 kg si douleur)"))
            .isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
    }

    /** Review of round 33: a range of doses over a period is the same ceiling as a range of times. */
    @Test
    void aRangeOfDosesIsNotASchedule() {
        for (String wording : List.of("1 a 3 cp par jour", "1-3 cp/j", "1-3cp/j", "1-3x/j")) {
            assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
        }
        assertThat(FrequencyWording.classify("1 a 2 cp 3 fois par jour")).as("a dose range, a fixed cadence")
            .isEqualTo(FrequencyWording.Verdict.FIXED);
    }

    /** S/P needs its slash; SP alone is a regimen, in any case. */
    @Test
    void onlyTheSlashMakesSpConditional() {
        assertThat(FrequencyWording.classify("1 cp s/p")).isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
        assertThat(FrequencyWording.classify("1 cp sp le matin")).isEqualTo(FrequencyWording.Verdict.FIXED);
        // QA D1434 (changed): a regimen and a duration state no cadence: a question, never a condition.
        assertThat(FrequencyWording.classify("SP/AQ 3 jours")).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
    }

    /**
     * QA D1419 (changed assertion: round 33 read any word nobody listed as a
     * condition, the wrong message that pushed regular courses into PRN): an
     * unknown word never makes a condition. Beside a cadence it changes nothing;
     * alone it is a question. A real condition word still reads as one.
     */
    @Test
    void anUnknownWordIsNeverACondition() {
        for (String wording : List.of("q6h vertiges", "toutes les 6 h zzz", "prendre 1 comprimé 3 fois par jour",
            "1 cp par jour pour HTA", "3 fois par jour pour infection", "1 fois par jour à heure précise")) {
            assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.FIXED);
        }
        for (String wording : List.of("zzz", "pour HTA", "selon ordonnance")) {
            assertThat(FrequencyWording.classify(wording)).as(wording).isEqualTo(FrequencyWording.Verdict.UNREADABLE);
        }
        assertThat(FrequencyWording.classify("3 fois par jour quand il tousse")).isEqualTo(FrequencyWording.Verdict.CONDITIONAL);
        assertThat(FrequencyWording.classify("matin et soir ou plus")).as("a ceiling").isEqualTo(FrequencyWording.Verdict.UNREADABLE);
    }

    /**
     * What the MAR reads of an order already written (QA D1426, changed
     * assertions, declared in the PR: "TPI-SP" and "Prise unique (TPI-SP 2)" were
     * UNREAD, "max 4/j" REPEATING, "1 fois par mois" UNREAD): the reader that
     * accepts a wording is the one the MAR reads. An older conditional wording on
     * an order not ticked keeps what it had.
     */
    @Test
    void theMarReadsWhatTheRuleAccepted() {
        Map<String, MarDoseSchedule.Kind> before = new HashMap<>();
        before.put("TPI-SP", MarDoseSchedule.Kind.ONE_OFF);
        before.put("Prise unique (TPI-SP 2)", MarDoseSchedule.Kind.ONE_OFF);
        before.put("pour douleur", MarDoseSchedule.Kind.UNREAD);
        before.put("max 4/j", MarDoseSchedule.Kind.UNREAD);
        before.put("toutes les 6 h pour douleur", MarDoseSchedule.Kind.REPEATING);
        before.put("si douleur", MarDoseSchedule.Kind.UNREAD);
        before.put("q6h", MarDoseSchedule.Kind.REPEATING);
        before.put("Prise unique", MarDoseSchedule.Kind.ONE_OFF);
        before.put("1 fois par mois", MarDoseSchedule.Kind.REPEATING);
        before.forEach((wording, kind) -> {
            Prescription rx = new Prescription();
            rx.setFrequency(wording);
            assertThat(MarDoseSchedule.read(rx).kind()).as(wording).isEqualTo(kind);
        });
    }

    /**
     * The mirror's word lists are this class's, name for name: a word added on
     * one side only would make the form and the server disagree on the next
     * wording that uses it.
     */
    @Test
    void theWordListsMatchThePortalMirror() throws Exception {
        String mirror = Files.readString(MIRROR, StandardCharsets.UTF_8);
        int start = mirror.indexOf("word-lists:start");
        int end = mirror.indexOf("word-lists:end");
        assertThat(start).as("the mirror's list markers").isPositive();
        Map<String, Set<String>> portal = new HashMap<>();
        Matcher list = Pattern.compile("const ([A-Z_]+) = words\\(([^;]*?)\\);", Pattern.DOTALL)
            .matcher(mirror.substring(start, end));
        while (list.find()) {
            Matcher literal = Pattern.compile("'([^']*)'").matcher(list.group(2));
            StringBuilder all = new StringBuilder();
            while (literal.find()) {
                all.append(literal.group(1));
            }
            portal.put(list.group(1), new TreeSet<>(Arrays.asList(all.toString().trim().split("\\s+"))));
        }
        int compared = 0;
        for (Class<?> reader : List.of(FrequencyWording.class, FrequencyCadence.class)) {
            for (Field field : reader.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == Set.class) {
                    Set<String> server = new TreeSet<>();
                    ((Set<?>) field.get(null)).forEach(word -> server.add(word.toString()));
                    server.remove("");
                    assertThat(portal.get(field.getName())).as(field.getName()).isEqualTo(server);
                    compared++;
                }
            }
        }
        assertThat(compared).as("the lists compared").isGreaterThan(10);
        assertThat(portal.keySet()).as("the same lists on both sides").hasSize(compared);
    }
}
