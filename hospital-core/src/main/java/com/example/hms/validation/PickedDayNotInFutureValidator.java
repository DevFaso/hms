package com.example.hms.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Validator behind {@link PickedDayNotInFuture}. */
public class PickedDayNotInFutureValidator implements ConstraintValidator<PickedDayNotInFuture, LocalDate> {

    private final Clock clock;

    public PickedDayNotInFutureValidator() {
        this(Clock.systemUTC());
    }

    PickedDayNotInFutureValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        return isAllowed(value, clock);
    }

    /**
     * True when {@code day} is null or not after tomorrow on the UTC calendar
     * of {@code clock}: a reader east of UTC is already on the next day while
     * the server is still on today. The day is read on the UTC calendar
     * whatever zone the clock carries, so the rule does not move with the
     * JVM's default zone.
     */
    public static boolean isAllowed(LocalDate day, Clock clock) {
        return day == null || !day.isAfter(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1));
    }
}
