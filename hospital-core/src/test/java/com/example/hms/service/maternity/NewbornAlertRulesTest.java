package com.example.hms.service.maternity;

import com.example.hms.enums.GlucoseUnit;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornDangerSignObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D1424: every sourced number, at and either side of its boundary. */
@DisplayName("D1424: the newborn warnings, from the sourced numbers")
class NewbornAlertRulesTest {

    private static final String NONE = "none";

    private static Map<String, NewbornAlertSeverity> codes(NewbornAlertRules.Reading reading) {
        return NewbornAlertRules.evaluate(reading).stream()
            .collect(Collectors.toMap(NewbornAlertRules.Finding::code, NewbornAlertRules.Finding::severity));
    }

    private static NewbornAlertRules.Reading heart(Integer value, boolean preterm, boolean previous) {
        return new NewbornAlertRules.Reading(null, value, null, null, null, null, null, null, 1L, preterm, previous);
    }

    private static NewbornAlertRules.Reading breaths(Integer value, boolean preterm) {
        return new NewbornAlertRules.Reading(null, null, value, null, null, null, null, null, 1L, preterm, false);
    }

    private static NewbornAlertRules.Reading glucose(Integer mgDl, Double mmol, Long hours) {
        return new NewbornAlertRules.Reading(null, null, null, null, mgDl, mmol, null, null, hours, false, false);
    }

    private static void assertExactly(Map<String, NewbornAlertSeverity> actual, String urgent, String notice) {
        if (NONE.equals(urgent)) {
            assertThat(actual).doesNotContainValue(NewbornAlertSeverity.URGENT);
        } else {
            assertThat(actual).containsEntry(urgent, NewbornAlertSeverity.URGENT);
        }
        if (NONE.equals(notice)) {
            assertThat(actual).doesNotContainValue(NewbornAlertSeverity.INFO);
        } else {
            assertThat(actual).containsEntry(notice, NewbornAlertSeverity.INFO);
        }
    }

    @ParameterizedTest(name = "HR {0}: urgent {1}, notice {2}")
    @CsvSource({
        "99,newborn-heart-rate,newborn-reference-heart-rate",
        "100,none,newborn-reference-heart-rate",
        "101,none,newborn-reference-heart-rate",
        "119,none,newborn-reference-heart-rate",
        "120,none,none",
        "121,none,none",
        "159,none,none",
        "160,newborn-heart-rate,none",
        "161,newborn-heart-rate,none",
        "169,newborn-heart-rate,none",
        "170,newborn-heart-rate,none",
        "171,newborn-heart-rate,newborn-reference-heart-rate",
        "0,newborn-heart-rate,newborn-reference-heart-rate",
        "1000,newborn-heart-rate,newborn-reference-heart-rate"
    })
    void heartRate(int value, String urgent, String notice) {
        assertExactly(codes(heart(value, false, false)), urgent, notice);
    }

    @ParameterizedTest(name = "RR {0}: urgent {1}, notice {2}")
    @CsvSource({
        "0,newborn-respiratory,newborn-reference-respiratory",
        "24,newborn-respiratory,newborn-reference-respiratory",
        "25,newborn-respiratory,none",
        "26,newborn-respiratory,none",
        "29,newborn-respiratory,none",
        "30,none,none",
        "31,none,none",
        "59,none,none",
        "60,newborn-respiratory,none",
        "61,newborn-respiratory,newborn-reference-respiratory",
        "85,newborn-respiratory,newborn-reference-respiratory",
        "200,newborn-respiratory,newborn-reference-respiratory"
    })
    void respiratoryRate(int value, String urgent, String notice) {
        assertExactly(codes(breaths(value, false)), urgent, notice);
    }

    @ParameterizedTest(name = "RR {0} asks for respiratory support as follow-up")
    @CsvSource({"29", "60", "70", "71"})
    @DisplayName("CR-06: an abnormal rate requests the follow-up; it records nothing as done")
    void respiratoryFollowUpIsARequest(int value) {
        assertThat(NewbornAlertRules.evaluate(breaths(value, false)))
            .filteredOn(f -> "newborn-respiratory".equals(f.code()))
            .singleElement()
            .satisfies(f -> assertThat(f.followUp()).isEqualTo(com.example.hms.enums.NewbornFollowUpAction.RESPIRATORY_SUPPORT));
    }

    @ParameterizedTest(name = "SpO2 {0}: {1}")
    @CsvSource({"89,newborn-oxygenation", "90,none", "91,none"})
    void saturation(int value, String urgent) {
        assertExactly(codes(new NewbornAlertRules.Reading(null, null, null, value, null, null, null, null, 1L, false, false)),
            urgent, NONE);
    }

    @ParameterizedTest(name = "T {0}: {1}")
    @CsvSource({
        "35.4,newborn-temperature-critical", "35.5,newborn-temperature-critical", "35.9,newborn-temperature-critical",
        "36.0,none", "36.1,none", "37.5,none",
        "37.6,newborn-temperature-who-danger", "37.9,newborn-temperature-who-danger",
        "38.0,newborn-temperature-critical", "38.1,newborn-temperature-critical"
    })
    void temperature(double value, String urgent) {
        assertExactly(codes(new NewbornAlertRules.Reading(value, null, null, null, null, null, null, null, 1L, false, false)),
            urgent, NONE);
    }

    @ParameterizedTest(name = "T {0} still cautions: {1}")
    @CsvSource({"36.0,true", "36.4,true", "36.5,false", "37.5,false", "37.6,false", "37.9,false"})
    @DisplayName("the older caution band stays below; CR-04: above 37.5 is urgent instead, never both")
    void temperatureCaution(double value, boolean caution) {
        Map<String, NewbornAlertSeverity> actual =
            codes(new NewbornAlertRules.Reading(value, null, null, null, null, null, null, null, 1L, false, false));
        assertThat(actual.containsKey("newborn-temperature")).isEqualTo(caution);
    }

    @ParameterizedTest(name = "T {0} requests prompt assessment")
    @CsvSource({"37.6", "37.9"})
    @DisplayName("CR-04: the provisional WHO band requests assessment; it asks for no thermal treatment")
    void theWhoBandRequestsAssessment(double value) {
        assertThat(NewbornAlertRules.evaluate(new NewbornAlertRules.Reading(value, null, null, null, null, null, null, null, 1L, false, false)))
            .singleElement()
            .satisfies(f -> {
                assertThat(f.severity()).isEqualTo(NewbornAlertSeverity.URGENT);
                assertThat(f.followUp()).isEqualTo(com.example.hms.enums.NewbornFollowUpAction.PEDIATRICIAN_NOTIFICATION);
            });
    }

    @Test
    @DisplayName("CR-04: the wording names the provisional WHO pathway and keeps NICE as another context, in every language")
    void theWordingNamesThePathway() {
        var messages = com.example.hms.i18n.TestMessageSources.bundles();
        assertThat(NewbornAlertRules.SELECTED_PATHWAY).contains("WHO", "PROVISIONAL");
        Map<java.util.Locale, List<String>> pathway = Map.of(
            java.util.Locale.ENGLISH, List.of("provisional WHO", "NICE", "37.5"),
            java.util.Locale.FRENCH, List.of("OMS provisoire", "NICE", "37,5"),
            java.util.Locale.forLanguageTag("es"), List.of("provisional de la OMS", "NICE", "37,5"));
        pathway.forEach((locale, words) -> {
            assertThat(messages.getMessage("newborn.alert.msg.newborn-temperature-who-danger", null, locale)).contains(words);
            assertThat(messages.getMessage("newborn.alert.msg.newborn-temperature-critical", null, locale)).contains(words);
        });
    }

    @ParameterizedTest(name = "{0} mg/dL at {1} h: {2} {3}")
    @CsvSource({
        "18,1,newborn-glucose,URGENT",
        "44,1,newborn-glucose,URGENT",
        "44,100,newborn-glucose,URGENT",
        "45,1,newborn-glucose-first-72h,URGENT",
        "46,71,newborn-glucose-first-72h,URGENT",
        "47,1,none,NONE",
        "48,1,none,NONE",
        "46,72,newborn-glucose-after-72h,CAUTION",
        "58,72,newborn-glucose-after-72h,CAUTION",
        "59,72,none,NONE",
        "60,72,none,NONE"
    })
    void glucoseInMgDl(int mgDl, long hours, String code, String severity) {
        assertGlucose(codes(glucose(mgDl, null, hours)), code, severity);
    }

    @ParameterizedTest(name = "{0} mmol/L at {1} h: {2} {3}")
    @CsvSource({
        "2.5,1,newborn-glucose-first-72h,URGENT",
        "2.59,1,newborn-glucose-first-72h,URGENT",
        "2.6,1,none,NONE",
        "2.61,1,none,NONE",
        "3.29,72,newborn-glucose-after-72h,CAUTION",
        "3.3,72,none,NONE",
        "3.31,72,none,NONE",
        "1.0,72,newborn-glucose,URGENT"
    })
    @DisplayName("a value in mmol/L is judged in mmol/L, not after rounding to mg/dL")
    void glucoseInMmol(double mmol, long hours, String code, String severity) {
        assertGlucose(codes(glucose(GlucoseUnit.MMOL_L.toMgDl(mmol), mmol, hours)), code, severity);
    }

    @ParameterizedTest(name = "{0} mg/dL as entered at {1} h: {2} {3}")
    @CsvSource({
        "46.6,1,newborn-glucose-first-72h,URGENT",
        "47.0,1,none,NONE",
        "58.6,72,newborn-glucose-after-72h,CAUTION",
        "59.0,72,none,NONE",
        "44.6,72,newborn-glucose,URGENT"
    })
    @DisplayName("a decimal in mg/dL is judged as entered, not after rounding")
    void glucoseDecimalInMgDl(double entered, long hours, String code, String severity) {
        assertGlucose(codes(new NewbornAlertRules.Reading(null, null, null, null, GlucoseUnit.MG_DL.toMgDl(entered), null,
            null, null, hours, false, false, entered)), code, severity);
    }

    @Test
    @DisplayName("2.49 mmol/L is below the older critical line, though it rounds to 45 mg/dL")
    void theOlderCriticalLineHoldsInMmol() {
        assertThat(codes(glucose(GlucoseUnit.MMOL_L.toMgDl(2.49), 2.49, 100L))).containsEntry("newborn-glucose", NewbornAlertSeverity.URGENT);
    }

    @ParameterizedTest(name = "T {0} asks for thermal support as follow-up")
    @CsvSource({"35.9", "38.0", "38.1"})
    @DisplayName("CR-06: an urgent temperature requests the follow-up; it records nothing as done")
    void temperatureFollowUpIsARequest(double value) {
        assertThat(NewbornAlertRules.evaluate(new NewbornAlertRules.Reading(value, null, null, null, null, null, null, null, 1L, false, false)))
            .filteredOn(f -> "newborn-temperature-critical".equals(f.code()))
            .singleElement()
            .satisfies(f -> assertThat(f.followUp()).isEqualTo(com.example.hms.enums.NewbornFollowUpAction.THERMAL_SUPPORT));
    }

    private static final NewbornAlertRules.Reading NO_VITALS =
        new NewbornAlertRules.Reading(null, null, null, null, null, null, null, null, 1L, false, false);
    private static final NewbornAlertRules.Reading NORMAL_VITALS =
        new NewbornAlertRules.Reading(36.8, 140, 40, 98, 70, null, null, null, 1L, false, false);

    /** One sign at the given observation, the others not assessed, built by position so a new sign cannot be missed. */
    private static NewbornAlertRules.DangerSigns signAt(int position, NewbornDangerSignObservation observation) throws ReflectiveOperationException {
        var components = NewbornAlertRules.DangerSigns.class.getRecordComponents();
        Object[] args = new Object[components.length];
        java.util.Arrays.fill(args, NewbornDangerSignObservation.NOT_ASSESSED);
        args[position] = observation;
        return (NewbornAlertRules.DangerSigns) NewbornAlertRules.DangerSigns.class
            .getDeclaredConstructor(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class[]::new))
            .newInstance(args);
    }

    private static final int DANGER_SIGNS = NewbornAlertRules.DangerSigns.class.getRecordComponents().length;

    @Test
    @DisplayName("CR-05: every sign present alone raises its own urgent finding, with its own code, on normal or no vitals")
    void eachDangerSignStandsAlone() throws ReflectiveOperationException {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < DANGER_SIGNS; i++) {
            for (NewbornAlertRules.Reading vitals : List.of(NO_VITALS, NORMAL_VITALS)) {
                assertThat(NewbornAlertRules.evaluate(vitals, signAt(i, NewbornDangerSignObservation.PRESENT)))
                    .singleElement()
                    .satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(NewbornAlertSeverity.URGENT);
                        assertThat(f.type()).isEqualTo(com.example.hms.enums.NewbornAlertType.DANGER_SIGN);
                        assertThat(f.followUp()).isNotNull();
                        seen.add(f.code());
                    });
            }
        }
        assertThat(seen).as("one code per sign").hasSize(DANGER_SIGNS).hasSize(4);
    }

    @Test
    @DisplayName("CR-05: absent, not assessed and blank raise nothing")
    void aSignNotPresentRaisesNothing() throws ReflectiveOperationException {
        for (int i = 0; i < DANGER_SIGNS; i++) {
            assertThat(NewbornAlertRules.evaluate(NORMAL_VITALS, signAt(i, NewbornDangerSignObservation.ABSENT))).isEmpty();
            assertThat(NewbornAlertRules.evaluate(NORMAL_VITALS, signAt(i, NewbornDangerSignObservation.NOT_ASSESSED))).isEmpty();
            assertThat(NewbornAlertRules.evaluate(NORMAL_VITALS, signAt(i, null))).isEmpty();
        }
    }

    @Test
    @DisplayName("CR-05: all present are four findings; the vitals' own findings stand beside them")
    void theSignsDoNotReplaceEachOtherOrTheVitals() {
        NewbornDangerSignObservation present = NewbornDangerSignObservation.PRESENT;
        NewbornAlertRules.Reading hot = new NewbornAlertRules.Reading(38.5, null, null, null, null, null, null, null, 1L, false, false);
        assertThat(NewbornAlertRules.evaluate(hot, new NewbornAlertRules.DangerSigns(present, present, present, present)))
            .extracting(NewbornAlertRules.Finding::code)
            .containsExactlyInAnyOrder("newborn-temperature-critical", "newborn-danger-poor-feeding", "newborn-danger-convulsions",
                "newborn-danger-chest-indrawing", "newborn-danger-no-movement");
    }

    @Test
    @DisplayName("CR-05: no sentence claims every WHO danger sign is covered")
    void noWordingClaimsFullCoverage() {
        var messages = com.example.hms.i18n.TestMessageSources.bundles();
        for (java.util.Locale locale : List.of(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es"))) {
            for (String code : List.of("poor-feeding", "convulsions", "chest-indrawing", "no-movement")) {
                assertThat(messages.getMessage("newborn.alert.msg.newborn-danger-" + code, null, locale).toLowerCase(locale))
                    .doesNotContain("all ", "every ", "complete", "tous ", "complet", "todos ", "completa");
            }
        }
    }

    @Test
    @DisplayName("a temperature above what other forms accept asks to remeasure, and stays urgent")
    void aHighTemperatureAsksToRemeasure() {
        assertThat(codes(new NewbornAlertRules.Reading(44.1, null, null, null, null, null, null, null, 1L, false, false)))
            .containsKeys("newborn-check-temperature", "newborn-temperature-critical");
        assertThat(codes(new NewbornAlertRules.Reading(44.0, null, null, null, null, null, null, null, 1L, false, false)))
            .doesNotContainKey("newborn-check-temperature");
    }

    @ParameterizedTest(name = "{0} mg/dL, age unknown: {1}")
    @CsvSource({
        "44,newborn-glucose", "45,newborn-glucose-unknown-age", "46,newborn-glucose-unknown-age", "47,none", "58,none"
    })
    @DisplayName("an unknown age takes the first-72-hours threshold and says so")
    void glucoseAgeUnknown(int mgDl, String code) {
        Map<String, NewbornAlertSeverity> actual = codes(glucose(mgDl, null, null));
        if (NONE.equals(code)) {
            assertThat(actual).isEmpty();
        } else {
            assertThat(actual).containsEntry(code, NewbornAlertSeverity.URGENT);
        }
    }

    private static void assertGlucose(Map<String, NewbornAlertSeverity> actual, String code, String severity) {
        if (NONE.equals(code)) {
            assertThat(actual).isEmpty();
        } else {
            assertThat(actual).containsEntry(code, NewbornAlertSeverity.valueOf(severity));
        }
    }

    @Test
    @DisplayName("a second reading of concern says it may be persistent; a first one only asks for a recheck")
    void heartRateRepeat() {
        assertThat(codes(heart(170, false, true))).containsKey("newborn-heart-rate-repeated").doesNotContainKey("newborn-heart-rate");
        assertThat(codes(heart(170, false, false))).containsKey("newborn-heart-rate").doesNotContainKey("newborn-heart-rate-repeated");
        assertThat(NewbornAlertRules.heartRateOfConcern(159)).isFalse();
        assertThat(NewbornAlertRules.heartRateOfConcern(160)).isTrue();
        assertThat(NewbornAlertRules.heartRateOfConcern(100)).isFalse();
        assertThat(NewbornAlertRules.heartRateOfConcern(99)).isTrue();
        assertThat(NewbornAlertRules.heartRateOfConcern(null)).isFalse();
    }

    @Test
    @DisplayName("a preterm baby gets the same numbers, and the notice says they are a term range")
    void pretermNotice() {
        assertThat(codes(heart(110, true, false))).containsOnlyKeys("newborn-reference-heart-rate-preterm");
        assertThat(codes(breaths(20, true))).containsKeys("newborn-respiratory", "newborn-reference-respiratory-preterm");
        assertThat(codes(heart(140, true, false))).isEmpty();
    }

    @ParameterizedTest(name = "{0}: asks to remeasure at {1}, not at {2}")
    @CsvSource({"301,300", "81,80"})
    @DisplayName("a value above what other forms accept is flagged, and the urgent alert still fires")
    void checkTheValue(int above, int at) {
        boolean heart = at == 300;
        String code = heart ? "newborn-check-heart-rate" : "newborn-check-respiratory";
        String urgent = heart ? "newborn-heart-rate" : "newborn-respiratory";
        NewbornAlertRules.Reading high = heart ? heart(above, false, false) : breaths(above, false);
        NewbornAlertRules.Reading edge = heart ? heart(at, false, false) : breaths(at, false);
        assertThat(codes(high)).containsEntry(code, NewbornAlertSeverity.CAUTION).containsEntry(urgent, NewbornAlertSeverity.URGENT);
        assertThat(codes(edge)).doesNotContainKey(code).containsEntry(urgent, NewbornAlertSeverity.URGENT);
    }

    @Test
    void checkTheOtherValues() {
        assertThat(codes(glucose(801, null, 100L))).containsKey("newborn-check-glucose");
        assertThat(codes(glucose(800, null, 100L))).doesNotContainKey("newborn-check-glucose");
        NewbornAlertRules.Reading pressure =
            new NewbornAlertRules.Reading(null, null, null, null, null, null, 301, 201, 1L, false, false);
        assertThat(NewbornAlertRules.evaluate(pressure)).extracting(NewbornAlertRules.Finding::code)
            .containsExactly("newborn-check-pressure");
    }

    @ParameterizedTest(name = "{0} h after birth")
    @CsvSource({"71,71", "72,72", "73,73"})
    void ageFromTheRecordedBirthTime(int hours, long expected) {
        LocalDateTime birth = LocalDateTime.of(2026, 11, 1, 8, 0);
        assertThat(NewbornAlertRules.hoursSinceBirth(birth, null, birth.plusHours(hours))).isEqualTo(expected);
    }

    @Test
    @DisplayName("a date of birth alone counts from the end of that day; no birth on record is an unknown age")
    void ageWithoutABirthTime() {
        LocalDate born = LocalDate.of(2026, 11, 1);
        assertThat(NewbornAlertRules.hoursSinceBirth(null, born, LocalDateTime.of(2026, 11, 5, 0, 0))).isEqualTo(72L);
        assertThat(NewbornAlertRules.hoursSinceBirth(null, born, LocalDateTime.of(2026, 11, 4, 23, 0))).isEqualTo(71L);
        assertThat(NewbornAlertRules.hoursSinceBirth(null, null, LocalDateTime.of(2026, 11, 5, 0, 0))).isNull();
    }

    @Test
    void theUnitConversionIsThePortals() {
        assertThat(GlucoseUnit.MMOL_L.toMgDl(5.6)).isEqualTo(101);
        assertThat(GlucoseUnit.MMOL_L.toMgDl(2.6)).isEqualTo(47);
        assertThat(GlucoseUnit.MG_DL.toMgDl(47)).isEqualTo(47);
    }

    @Test
    @DisplayName("every code the rules raise has its sentence and trigger in EN, FR and ES")
    void everyCodeIsWordedInEveryLanguage() {
        var messages = com.example.hms.i18n.TestMessageSources.bundles();
        List<String> codes = List.of("newborn-temperature-critical", "newborn-temperature-who-danger", "newborn-temperature", "newborn-heart-rate",
            "newborn-heart-rate-repeated", "newborn-respiratory", "newborn-oxygenation", "newborn-glucose",
            "newborn-glucose-first-72h", "newborn-glucose-unknown-age", "newborn-glucose-after-72h",
            "newborn-reference-heart-rate", "newborn-reference-heart-rate-preterm", "newborn-reference-respiratory",
            "newborn-reference-respiratory-preterm", "newborn-check-heart-rate", "newborn-check-respiratory",
            "newborn-check-glucose", "newborn-check-pressure", "newborn-check-temperature",
            "newborn-danger-poor-feeding", "newborn-danger-convulsions", "newborn-danger-chest-indrawing", "newborn-danger-no-movement");
        for (java.util.Locale locale : List.of(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.forLanguageTag("es"))) {
            for (String code : codes) {
                assertThat(messages.getMessage("newborn.alert.msg." + code, null, locale)).as(code).isNotBlank();
                assertThat(messages.getMessage("newborn.alert.trigger." + code, null, locale)).as(code).isNotBlank();
            }
            assertThat(messages.getMessage("newborn.alert.msg.newborn-oxygenation", null, locale)).contains("90");
            assertThat(messages.getMessage("newborn.alert.msg.newborn-reference-heart-rate", null, locale)).contains("120-170");
        }
    }

    /** QA D1482: the check flag reads both ends of what the other forms accept. */
    @org.junit.jupiter.api.Test
    @DisplayName("a value below what other forms accept asks to remeasure too")
    void aLowValueAsksToRemeasure() {
        assertThat(codes(new NewbornAlertRules.Reading(10.0, null, null, null, null, null, null, null, 1L, false, false)))
            .containsKeys("newborn-check-temperature", "newborn-temperature-critical");
        assertThat(codes(new NewbornAlertRules.Reading(25.0, null, null, null, null, null, null, null, 1L, false, false)))
            .doesNotContainKey("newborn-check-temperature");
        assertThat(codes(heart(5, false, false))).containsKey("newborn-check-heart-rate");
        assertThat(codes(heart(20, false, false))).doesNotContainKey("newborn-check-heart-rate");
        assertThat(codes(breaths(2, false))).containsKey("newborn-check-respiratory");
        assertThat(codes(glucose(5, null, 100L))).containsKey("newborn-check-glucose");
    }
}
