package com.example.hms.service.impl;

import com.example.hms.enums.ImmunizationStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.PatientImmunization;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.validation.PickedDayNotInFutureValidator;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * QA D1425: a vaccination dated in the future or before the patient's birth,
 * dose 0, -1 or 5 of 2, a next dose due before this one and a lot expired
 * before the injection were all saved as completed. The one rule of the create
 * and the edit; each refusal names its field.
 *
 * <p>It runs here and not as constraints on the request because the request is
 * also what an edit echoes. {@code current} is the stored row on an edit: a
 * value the edit does not change is not judged, so a row saved before this rule
 * stays readable and stays editable on its other fields, until someone touches
 * the wrong value itself.
 */
final class ImmunizationPlausibility {

    private ImmunizationPlausibility() {
    }

    static void require(ImmunizationRequestDTO request, LocalDate dateOfBirth, PatientImmunization current) {
        requireAt(request, dateOfBirth, current, Clock.systemUTC());
    }

    static void requireAt(ImmunizationRequestDTO request, LocalDate dateOfBirth, PatientImmunization current, Clock clock) {
        requireAdministrationDate(request, dateOfBirth, current, clock);
        requireDose(request, current);
        requireProductAndNextDose(request, current);
    }

    private static void requireAdministrationDate(ImmunizationRequestDTO request, LocalDate dateOfBirth,
                                                  PatientImmunization current, Clock clock) {
        LocalDate date = request.getAdministrationDate();
        if (date == null || (current != null && date.equals(current.getAdministrationDate()))) {
            return;
        }
        // A deferred dose names the day it is put off to; every other status records a day that happened.
        if (request.getStatus() != ImmunizationStatus.DEFERRED && !PickedDayNotInFutureValidator.isAllowed(date, clock)) {
            throw new FieldBusinessException("administrationDate", "immunization.r34.dateFuture");
        }
        if (dateOfBirth != null && date.isBefore(dateOfBirth)) {
            throw new FieldBusinessException("administrationDate", "immunization.r34.dateBeforeBirth");
        }
    }

    private static void requireDose(ImmunizationRequestDTO request, PatientImmunization current) {
        Integer dose = request.getDoseNumber();
        Integer series = request.getTotalDosesInSeries();
        Integer storedDose = current == null ? null : current.getDoseNumber();
        Integer storedSeries = current == null ? null : current.getTotalDosesInSeries();
        boolean doseChanged = current == null || !Objects.equals(dose, storedDose);
        boolean seriesChanged = current == null || !Objects.equals(series, storedSeries);
        if (dose != null && dose < 1 && doseChanged) {
            throw new FieldBusinessException("doseNumber", "immunization.r34.doseBelowOne");
        }
        if (series != null && series < 1 && seriesChanged) {
            throw new FieldBusinessException("totalDosesInSeries", "immunization.r34.seriesBelowOne");
        }
        if (dose != null && series != null && dose > series && (doseChanged || seriesChanged)) {
            throw new FieldBusinessException("doseNumber", "immunization.r34.doseAboveSeries");
        }
        Double quantity = request.getDoseQuantity();
        if (quantity != null && quantity < 0
            && (current == null || !Objects.equals(quantity, current.getDoseQuantity()))) {
            throw new FieldBusinessException("doseQuantity", "immunization.r34.quantityNegative");
        }
    }

    private static void requireProductAndNextDose(ImmunizationRequestDTO request, PatientImmunization current) {
        LocalDate date = request.getAdministrationDate();
        if (date == null) {
            return;
        }
        LocalDate storedDate = current == null ? null : current.getAdministrationDate();
        boolean dateChanged = current == null || !date.equals(storedDate);
        LocalDate next = request.getNextDoseDueDate();
        if (next != null && next.isBefore(date)
            && (dateChanged || !next.equals(current.getNextDoseDueDate()))) {
            throw new FieldBusinessException("nextDoseDueDate", "immunization.r34.nextDoseBeforeDose");
        }
        LocalDate expiry = request.getExpirationDate();
        // Only a dose that was given used the lot: a refused or missed one may name an expired one.
        if (request.getStatus() == ImmunizationStatus.COMPLETED && expiry != null && expiry.isBefore(date)
            && (dateChanged || !expiry.equals(current.getExpirationDate()))) {
            throw new FieldBusinessException("expirationDate", "immunization.r34.lotExpired");
        }
    }
}
