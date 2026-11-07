package com.example.hms.service.impl;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.validation.OnCreate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA round 30 (user decision 2026-11-02, option a): the minimum interval of
 * an as-needed order is written by the prescriber, required on a new
 * as-needed order, refused on any other, never invented. QA D1378 (round 32):
 * as-needed is the prescriber's explicit tick; a conditional frequency
 * without it is refused on {@code asNeeded} (the safety net). And QA D1359:
 * the prescribed route is one the route reading knows.
 */
class PrnMinIntervalTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void validator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    /**
     * QA D1378 (round 32): an order the prescriber ticked as needed needs its
     * interval, whatever its frequency says ("q4h", "SOS", nothing at all).
     */
    @ParameterizedTest
    @ValueSource(strings = {"PRN", "si besoin", "SOS", "q4h", "toutes les 6 heures", ""})
    void aTickedOrderWithoutItsIntervalIsRefusedOnTheField(String frequency) {
        Prescription rx = new Prescription();
        rx.setFrequency(frequency);
        rx.setAsNeeded(true);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.REQUIRED_KEY);
        rx.setPrnMinIntervalHours(4);
        PrnMinInterval.applyTo(rx);
        assertThat(rx.getPrnMinIntervalHours()).isEqualTo(4);
    }

    /**
     * QA D1378 replay (round 32), QA D1360: a controlled morphine order written
     * with ANY as-needed wording - the round-31 phrases and the ~45 round-32
     * ones (SOS, p.r.n., sur demande, si precisa, when necessary ...) - and not
     * ticked is refused on {@code asNeeded} (the safety net), at the edge with
     * the other refusals and in the service (every write path). Ticked, it
     * needs its interval; ticked with the interval, it passes.
     */
    @ParameterizedTest
    @MethodSource({"com.example.hms.service.impl.MarDoseScheduleTest#asNeededCases",
        "com.example.hms.service.impl.MarDoseScheduleTest#conditionalCases"})
    void d1378_aMorphineOrderInAnyAsNeededWordingIsConfirmed(String frequency) {
        PrescriptionRequestDTO request = morphine(frequency);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .contains(PrnMinInterval.AS_NEEDED_FIELD).doesNotContain(PrnMinInterval.FIELD);
        request.setAsNeeded(false);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .contains(PrnMinInterval.AS_NEEDED_FIELD);
        request.setAsNeeded(true);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .contains(PrnMinInterval.FIELD).doesNotContain(PrnMinInterval.AS_NEEDED_FIELD);
        request.setPrnMinIntervalHours(4);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .doesNotContain(PrnMinInterval.FIELD, PrnMinInterval.AS_NEEDED_FIELD);

        Prescription rx = new Prescription();
        rx.setMedicationName(request.getMedicationName());
        rx.setControlledSubstance(true);
        rx.setFrequency(frequency);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx)).as(frequency)
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.AS_NEEDED_FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.CONFIRM_KEY);
        rx.setAsNeeded(true);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx)).as(frequency)
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.REQUIRED_KEY);
        rx.setPrnMinIntervalHours(4);
        PrnMinInterval.applyTo(rx);
    }

    /** QA D1378: a fixed schedule, not ticked, is neither asked to confirm nor for an interval. */
    @ParameterizedTest
    @MethodSource("com.example.hms.service.impl.MarDoseScheduleTest#fixedScheduleCases")
    void d1378_aFixedScheduleIsNotAskedAnything(String frequency) {
        assertThat(paths(morphine(frequency), Default.class, OnCreate.class)).as(frequency)
            .doesNotContain(PrnMinInterval.FIELD, PrnMinInterval.AS_NEEDED_FIELD);
        Prescription rx = new Prescription();
        rx.setFrequency(frequency);
        PrnMinInterval.applyTo(rx);
        assertThat(rx.getPrnMinIntervalHours()).isNull();
    }

    private static PrescriptionRequestDTO morphine(String frequency) {
        PrescriptionRequestDTO r = request(frequency);
        r.setMedicationName("Morphine 2 mg");
        r.setRoute("IV");
        r.setControlledSubstance(true);
        return r;
    }

    @Test
    void anOutOfRangeIntervalIsRefusedOnTheServiceToo() {
        Prescription rx = new Prescription();
        rx.setFrequency("PRN");
        rx.setAsNeeded(true);
        rx.setPrnMinIntervalHours(0);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.RANGE_KEY);
        rx.setPrnMinIntervalHours(PrnMinInterval.MAX_HOURS + 1);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.RANGE_KEY);
    }

    /** QA D1378: an interval on an order not ticked as needed is refused, never silently dropped. */
    @Test
    void anIntervalOnAnOrderNotTickedIsRefused() {
        Prescription rx = new Prescription();
        rx.setFrequency("3 fois par jour");
        rx.setPrnMinIntervalHours(6);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.NOT_AS_NEEDED_KEY);
    }

    @Test
    void theRequestNamesTheMissingIntervalWithTheOtherRefusalsOnCreate() {
        PrescriptionRequestDTO request = request("si besoin");
        request.setAsNeeded(true);
        assertThat(paths(request, Default.class, OnCreate.class)).contains(PrnMinInterval.FIELD);
        // An edit or a withdrawal (Default group only) is not refused for it at the edge.
        assertThat(paths(request, Default.class)).doesNotContain(PrnMinInterval.FIELD);
        request.setPrnMinIntervalHours(4);
        assertThat(paths(request, Default.class, OnCreate.class)).doesNotContain(PrnMinInterval.FIELD);
    }

    /**
     * QA D1378: a withdrawal (Default group, an update) that echoes an older
     * unticked order's "SOS" frequency is never refused by the safety net.
     */
    @Test
    void aWithdrawalEchoingAnUntickedAsNeededWordingIsNotRefused() {
        PrescriptionRequestDTO echo = request("SOS");
        assertThat(paths(echo, Default.class)).doesNotContain(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.FIELD);
        echo.setAsNeeded(false);
        assertThat(paths(echo, Default.class)).doesNotContain(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.FIELD);
    }

    @Test
    void theRangeAndAnIntervalOnAnOrderNotTickedAreRefusedOnTheField() {
        PrescriptionRequestDTO prn = request("PRN");
        prn.setAsNeeded(true);
        prn.setPrnMinIntervalHours(0);
        assertThat(paths(prn, Default.class)).contains(PrnMinInterval.FIELD);
        prn.setPrnMinIntervalHours(73);
        assertThat(paths(prn, Default.class)).contains(PrnMinInterval.FIELD);
        PrescriptionRequestDTO tid = request("TID");
        tid.setPrnMinIntervalHours(6);
        // Not ticked (absent = not as-needed on create; false on any request).
        assertThat(paths(tid, Default.class, OnCreate.class)).contains(PrnMinInterval.FIELD);
        tid.setAsNeeded(false);
        assertThat(paths(tid, Default.class)).contains(PrnMinInterval.FIELD);
        tid.setPrnMinIntervalHours(null);
        assertThat(paths(tid, Default.class, OnCreate.class)).doesNotContain(PrnMinInterval.FIELD);
    }

    /** QA D1359: "BOGUS" and "par la bouche" were stored as the route; the form's codes and their names pass. */
    @Test
    void d1359_anUnknownRouteIsRefusedOnTheRouteField() {
        PrescriptionRequestDTO request = request("TID");
        for (String unknown : new String[] {"BOGUS", "par la bouche", "xyz"}) {
            request.setRoute(unknown);
            assertThat(paths(request, Default.class, OnCreate.class)).as(unknown).contains("route");
            // An edit is judged by the service against the order's own route (a withdrawal may echo it).
            assertThat(paths(request, Default.class)).as(unknown).doesNotContain("route");
        }
        for (String known : new String[] {"PO", "IV", "Intraveineuse (IV)", "oral", "Sous-cutanée", "nebulisation",
            "", null}) {
            request.setRoute(known);
            assertThat(paths(request, Default.class, OnCreate.class)).as(String.valueOf(known))
                .doesNotContain("route");
        }
    }

    /** QA D1395: each kind of refusal says what is wrong, on the tick. */
    @Test
    void d1395_theTwoRefusalsAreDistinct() {
        assertThat(PrnMinInterval.safetyNet("pour douleur"))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.AS_NEEDED_FIELD, PrnMinInterval.CONFIRM_KEY));
        assertThat(PrnMinInterval.safetyNet("selon prescription m\u00e9dicale"))
            .isEqualTo(new PrnMinInterval.Refusal(PrnMinInterval.CONFIRMED_FIELD, PrnMinInterval.UNREADABLE_KEY));
        assertThat(PrnMinInterval.safetyNet("q6h")).isNull();
        assertThat(PrnMinInterval.safetyNet(null)).isNull();
    }

    /** QA D1419 / D1434: a wording the MAR cannot schedule is a question at create, controlled drug included. */
    @ParameterizedTest
    @ValueSource(strings = {"SP + AQ", "selon ordonnance", "CPS", "protocole national", "max 4/j", "1 à 3 fois par jour",
        "5 jours", "pendant 3 jours", "par jour", "x3"})
    void d1419_aWordingNotReadIsAskedOnceThenCreated(String frequency) {
        Prescription rx = new Prescription();
        rx.setMedicationName("Morphine");
        rx.setControlledSubstance(true);
        rx.setFrequency(frequency);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx)).as(frequency)
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.CONFIRMED_FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.UNREADABLE_KEY);
        PrnMinInterval.applyTo(rx, true);
        assertThat(rx.isAsNeeded()).as("never nudged into as-needed").isFalse();

        PrescriptionRequestDTO request = new PrescriptionRequestDTO();
        request.setMedicationName("Morphine");
        request.setFrequency(frequency);
        request.setAsNeeded(false);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .contains(PrnMinInterval.CONFIRMED_FIELD).doesNotContain(PrnMinInterval.AS_NEEDED_FIELD);
        request.setFrequencyConfirmed(true);
        assertThat(paths(request, Default.class, OnCreate.class)).as(frequency)
            .doesNotContain(PrnMinInterval.CONFIRMED_FIELD, PrnMinInterval.AS_NEEDED_FIELD);
    }

    /** QA D1419: an edit that changes the frequency is asked; one that leaves it is not; a condition stays refused. */
    @Test
    void d1419_anEditIsAskedOnlyWhenItWritesTheWording() {
        Prescription rx = new Prescription();
        rx.setFrequency("selon protocole");
        PrnMinInterval.applyToEdit(rx, "selon protocole", false);
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "q6h", false))
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.CONFIRMED_FIELD);
        PrnMinInterval.applyToEdit(rx, "q6h", false, true);
        rx.setFrequency("pour douleur");
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "q6h", false, true))
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.AS_NEEDED_FIELD);
    }

    /** QA D1433: a fraction is refused on the field, not cut down; no sibling Integer is read from a fraction. */
    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2.9", "6.5", "0.5", "\"1.5\"", "\"4294967297\"", "4294967297.0"})
    void d1433_aFractionOfAnHourIsRefusedOnItsField(String json) {
        PrescriptionRequestDTO read = tools.jackson.databind.json.JsonMapper.builder().build().readValue(
            "{\"medicationName\":\"Paracetamol\",\"frequency\":\"si besoin\",\"asNeeded\":true,"
                + "\"prnMinIntervalHours\":" + json + "}", PrescriptionRequestDTO.class);
        assertThat(paths(read, Default.class, OnCreate.class)).as(json).contains(PrnMinInterval.FIELD);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {"4|4", "2.0|2", "\"6\"|6", "72|72", "1|1"})
    void d1433_aWholeNumberStaysAWholeNumber(String json, int hours) {
        PrescriptionRequestDTO read = tools.jackson.databind.json.JsonMapper.builder().build().readValue(
            "{\"medicationName\":\"Paracetamol\",\"frequency\":\"si besoin\",\"asNeeded\":true,"
                + "\"prnMinIntervalHours\":" + json + "}", PrescriptionRequestDTO.class);
        assertThat(read.getPrnMinIntervalHours()).isEqualTo(hours);
        assertThat(paths(read, Default.class, OnCreate.class)).doesNotContain(PrnMinInterval.FIELD);
    }

    /** QA D1391 / D1395: an edit that writes no frequency is not asked about an old one; one that does is. */
    @Test
    void d1391_anEditIsJudgedOnWhatItWrote() {
        Prescription rx = new Prescription();
        rx.setFrequency("SOS");
        PrnMinInterval.applyToEdit(rx, "SOS", false);
        PrnMinInterval.applyToEdit(rx, " SOS ", false);

        rx.setFrequency("pour douleur");
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "SOS", false))
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.AS_NEEDED_FIELD);

        // Unticking keeps the old text, and that text is now the prescriber's statement of a fixed schedule.
        rx.setFrequency("SOS");
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "SOS", true))
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.AS_NEEDED_FIELD);

        // The interval half applies either way.
        rx.setPrnMinIntervalHours(4);
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "SOS", false))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.NOT_AS_NEEDED_KEY);

        // Ticked: the interval rule, whatever the frequency did.
        rx.setAsNeeded(true);
        rx.setPrnMinIntervalHours(null);
        assertThatThrownBy(() -> PrnMinInterval.applyToEdit(rx, "SOS", true))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.REQUIRED_KEY);
        PrnMinInterval.applyToEdit(null, null, false);
    }

    /** QA D1397: 24 / interval, only for a ticked order with an interval. */
    @Test
    void d1397_theHighestDosesADayComeFromTheInterval() {
        assertThat(PrnMinInterval.highestDosesPerDay(true, 1)).isEqualTo(24d);
        assertThat(PrnMinInterval.highestDosesPerDay(true, 6)).isEqualTo(4d);
        assertThat(PrnMinInterval.highestDosesPerDay(true, 48)).isEqualTo(0.5d);
        assertThat(PrnMinInterval.highestDosesPerDay(false, 6)).isNull();
        assertThat(PrnMinInterval.highestDosesPerDay(true, null)).isNull();
        assertThat(PrnMinInterval.highestDosesPerDay(true, 0)).isNull();
    }

    private static PrescriptionRequestDTO request(String frequency) {
        PrescriptionRequestDTO r = new PrescriptionRequestDTO();
        r.setPatientId(UUID.randomUUID());
        r.setMedicationName("Paracétamol 500 mg");
        r.setQuantity(BigDecimal.TEN);
        r.setFrequency(frequency);
        return r;
    }

    private static Set<String> paths(PrescriptionRequestDTO request, Class<?>... groups) {
        Set<ConstraintViolation<PrescriptionRequestDTO>> violations = validator.validate(request, groups);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
