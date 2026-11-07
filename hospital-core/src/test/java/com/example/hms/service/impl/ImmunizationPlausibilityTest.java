package com.example.hms.service.impl;

import com.example.hms.enums.ImmunizationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.PatientImmunization;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1425: the one rule of a vaccination's create and edit; every refusal names its field. */
@DisplayName("D1425: a vaccination's dates and doses")
class ImmunizationPlausibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final LocalDate BORN = LocalDate.of(2022, 2, 2);

    private static ImmunizationRequestDTO dose() {
        return ImmunizationRequestDTO.builder()
            .administrationDate(TODAY.minusDays(10))
            .status(ImmunizationStatus.COMPLETED)
            .doseNumber(1)
            .totalDosesInSeries(2)
            .build();
    }

    private static PatientImmunization storedFrom(ImmunizationRequestDTO dto) {
        PatientImmunization row = new PatientImmunization();
        row.setAdministrationDate(dto.getAdministrationDate());
        row.setDoseNumber(dto.getDoseNumber());
        row.setTotalDosesInSeries(dto.getTotalDosesInSeries());
        row.setDoseQuantity(dto.getDoseQuantity());
        row.setNextDoseDueDate(dto.getNextDoseDueDate());
        row.setExpirationDate(dto.getExpirationDate());
        return row;
    }

    private static void refusedOn(String field, String key, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
            org.assertj.core.api.Assertions.assertThat(ex.getField()).isEqualTo(field);
            org.assertj.core.api.Assertions.assertThat(ex.getMessage()).isNotBlank().doesNotContain("immunization.r34." + key + " ");
        });
    }

    private static void create(ImmunizationRequestDTO dto) {
        ImmunizationPlausibility.requireAt(dto, BORN, null, CLOCK);
    }

    @Test
    void aPlainDosePasses() {
        assertThatCode(() -> create(dose())).doesNotThrowAnyException();
    }

    @Test
    void aFutureDateIsRefused() {
        ImmunizationRequestDTO dto = dose();
        dto.setAdministrationDate(TODAY.plusDays(30));
        refusedOn("administrationDate", "dateFuture", () -> create(dto));
    }

    @Test
    void todayPassesAndADeferredDoseMayNameALaterDay() {
        ImmunizationRequestDTO today = dose();
        today.setAdministrationDate(TODAY);
        assertThatCode(() -> create(today)).doesNotThrowAnyException();

        ImmunizationRequestDTO deferred = dose();
        deferred.setStatus(ImmunizationStatus.DEFERRED);
        deferred.setAdministrationDate(TODAY.plusDays(30));
        assertThatCode(() -> create(deferred)).doesNotThrowAnyException();
    }

    @Test
    void aDateBeforeBirthIsRefusedAndAnUnknownBirthDateJudgesNothing() {
        ImmunizationRequestDTO dto = dose();
        dto.setAdministrationDate(LocalDate.of(2019, 5, 5));
        refusedOn("administrationDate", "dateBeforeBirth", () -> create(dto));
        assertThatCode(() -> ImmunizationPlausibility.requireAt(dto, null, null, CLOCK)).doesNotThrowAnyException();
        ImmunizationRequestDTO atBirth = dose();
        atBirth.setAdministrationDate(BORN);
        assertThatCode(() -> create(atBirth)).doesNotThrowAnyException();
    }

    @Test
    void doseBelowOneAndAboveTheSeriesAreRefused() {
        for (int bad : new int[]{0, -1}) {
            ImmunizationRequestDTO dto = dose();
            dto.setDoseNumber(bad);
            refusedOn("doseNumber", "doseBelowOne", () -> create(dto));
        }
        ImmunizationRequestDTO above = dose();
        above.setDoseNumber(5);
        refusedOn("doseNumber", "doseAboveSeries", () -> create(above));
        ImmunizationRequestDTO series = dose();
        series.setTotalDosesInSeries(0);
        refusedOn("totalDosesInSeries", "seriesBelowOne", () -> create(series));
    }

    @Test
    void aDoseWithNoNumberOrNoSeriesIsNotJudged() {
        ImmunizationRequestDTO dto = dose();
        dto.setDoseNumber(null);
        dto.setTotalDosesInSeries(null);
        assertThatCode(() -> create(dto)).doesNotThrowAnyException();
    }

    @Test
    void aNegativeQuantityIsRefused() {
        ImmunizationRequestDTO dto = dose();
        dto.setDoseQuantity(-1.0);
        refusedOn("doseQuantity", "quantityNegative", () -> create(dto));
    }

    @Test
    void aNextDoseBeforeThisOneIsRefused() {
        ImmunizationRequestDTO dto = dose();
        dto.setNextDoseDueDate(dto.getAdministrationDate().minusDays(1));
        refusedOn("nextDoseDueDate", "nextDoseBeforeDose", () -> create(dto));
    }

    @Test
    void aLotExpiredBeforeTheInjectionIsRefusedOnlyWhenTheDoseWasGiven() {
        ImmunizationRequestDTO dto = dose();
        dto.setExpirationDate(dto.getAdministrationDate().minusDays(1));
        refusedOn("expirationDate", "lotExpired", () -> create(dto));
        dto.setStatus(ImmunizationStatus.REFUSED);
        assertThatCode(() -> create(dto)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an edit that only echoes the old wrong values saves: the row stays editable on its other fields")
    void anEditDoesNotJudgeWhatItDoesNotChange() {
        ImmunizationRequestDTO legacy = dose();
        legacy.setAdministrationDate(TODAY.plusDays(30));
        legacy.setDoseNumber(-1);
        legacy.setTotalDosesInSeries(0);
        legacy.setDoseQuantity(-1.0);
        legacy.setNextDoseDueDate(TODAY);
        legacy.setExpirationDate(TODAY);
        PatientImmunization stored = storedFrom(legacy);

        assertThatCode(() -> ImmunizationPlausibility.requireAt(legacy, BORN, stored, CLOCK)).doesNotThrowAnyException();

        ImmunizationRequestDTO beforeBirth = dose();
        beforeBirth.setAdministrationDate(LocalDate.of(2019, 5, 5));
        assertThatCode(() -> ImmunizationPlausibility.requireAt(beforeBirth, BORN, storedFrom(beforeBirth), CLOCK))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("turning a row into a completed dose claims its date and its lot, even unchanged; voiding it does not")
    void aStatusFlipIsJudged() {
        ImmunizationRequestDTO deferred = dose();
        deferred.setStatus(ImmunizationStatus.COMPLETED);
        deferred.setAdministrationDate(TODAY.plusDays(30));
        PatientImmunization storedDeferred = storedFrom(deferred);
        storedDeferred.setStatus("DEFERRED");
        refusedOn("administrationDate", "dateFuture",
            () -> ImmunizationPlausibility.requireAt(deferred, BORN, storedDeferred, CLOCK));

        ImmunizationRequestDTO refused = dose();
        refused.setExpirationDate(refused.getAdministrationDate().minusDays(1));
        PatientImmunization storedRefused = storedFrom(refused);
        storedRefused.setStatus("REFUSED");
        refusedOn("expirationDate", "lotExpired",
            () -> ImmunizationPlausibility.requireAt(refused, BORN, storedRefused, CLOCK));

        ImmunizationRequestDTO voided = dose();
        voided.setStatus(ImmunizationStatus.ENTERED_IN_ERROR);
        voided.setAdministrationDate(TODAY.plusDays(30));
        PatientImmunization storedWrong = storedFrom(voided);
        storedWrong.setStatus("COMPLETED");
        assertThatCode(() -> ImmunizationPlausibility.requireAt(voided, BORN, storedWrong, CLOCK)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an edit that changes a wrong value to another wrong value is refused")
    void anEditIsJudgedOnWhatItChanges() {
        ImmunizationRequestDTO legacy = dose();
        legacy.setDoseNumber(5);
        PatientImmunization stored = storedFrom(legacy);

        ImmunizationRequestDTO edit = dose();
        edit.setDoseNumber(4);
        refusedOn("doseNumber", "doseAboveSeries", () -> ImmunizationPlausibility.requireAt(edit, BORN, stored, CLOCK));

        ImmunizationRequestDTO fixed = dose();
        assertThatCode(() -> ImmunizationPlausibility.requireAt(fixed, BORN, stored, CLOCK)).doesNotThrowAnyException();

        ImmunizationRequestDTO movedDate = dose();
        PatientImmunization withNext = storedFrom(dose());
        withNext.setNextDoseDueDate(TODAY.minusDays(5));
        movedDate.setNextDoseDueDate(TODAY.minusDays(5));
        movedDate.setAdministrationDate(TODAY.minusDays(1));
        refusedOn("nextDoseDueDate", "nextDoseBeforeDose", () -> ImmunizationPlausibility.requireAt(movedDate, BORN, withNext, CLOCK));
    }
}
