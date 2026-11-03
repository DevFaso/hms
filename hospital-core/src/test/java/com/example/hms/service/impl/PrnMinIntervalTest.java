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
 * as-needed order, refused on any other, never invented. And QA D1359: the
 * prescribed route is one the route reading knows.
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

    @ParameterizedTest
    @ValueSource(strings = {"PRN", "si besoin", "Si besoin, max 4/j", "as needed", "si es necesario"})
    void anAsNeededOrderWithoutItsIntervalIsRefusedOnTheField(String frequency) {
        Prescription rx = new Prescription();
        rx.setFrequency(frequency);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .isInstanceOf(FieldBusinessException.class)
            .hasFieldOrPropertyWithValue("field", PrnMinInterval.FIELD)
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.REQUIRED_KEY);
        rx.setPrnMinIntervalHours(4);
        PrnMinInterval.applyTo(rx);
        assertThat(rx.getPrnMinIntervalHours()).isEqualTo(4);
    }

    @Test
    void anOutOfRangeIntervalIsRefusedOnTheServiceToo() {
        Prescription rx = new Prescription();
        rx.setFrequency("PRN");
        rx.setPrnMinIntervalHours(0);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.RANGE_KEY);
        rx.setPrnMinIntervalHours(PrnMinInterval.MAX_HOURS + 1);
        assertThatThrownBy(() -> PrnMinInterval.applyTo(rx))
            .hasFieldOrPropertyWithValue("messageKey", PrnMinInterval.RANGE_KEY);
    }

    @Test
    void anEditToAFixedScheduleDropsTheInterval() {
        Prescription rx = new Prescription();
        rx.setFrequency("3 fois par jour");
        rx.setPrnMinIntervalHours(6);
        PrnMinInterval.applyTo(rx);
        assertThat(rx.getPrnMinIntervalHours()).isNull();
    }

    @Test
    void theRequestNamesTheMissingIntervalWithTheOtherRefusalsOnCreate() {
        PrescriptionRequestDTO request = request("si besoin");
        assertThat(paths(request, Default.class, OnCreate.class)).contains(PrnMinInterval.FIELD);
        // An edit or a withdrawal (Default group only) is not refused for it at the edge.
        assertThat(paths(request, Default.class)).doesNotContain(PrnMinInterval.FIELD);
        request.setPrnMinIntervalHours(4);
        assertThat(paths(request, Default.class, OnCreate.class)).doesNotContain(PrnMinInterval.FIELD);
    }

    @Test
    void theRangeAndAnIntervalOnAFixedScheduleAreRefusedOnTheField() {
        PrescriptionRequestDTO prn = request("PRN");
        prn.setPrnMinIntervalHours(0);
        assertThat(paths(prn, Default.class)).contains(PrnMinInterval.FIELD);
        prn.setPrnMinIntervalHours(73);
        assertThat(paths(prn, Default.class)).contains(PrnMinInterval.FIELD);
        PrescriptionRequestDTO tid = request("TID");
        tid.setPrnMinIntervalHours(6);
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
