package com.example.hms.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.UnexpectedTypeException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/** Validator behind {@link NotInClinicalFuture}. */
public class NotInClinicalFutureValidator implements ConstraintValidator<NotInClinicalFuture, Object> {

    /** Tolerated lead of a charting device's clock over the server's. */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(10);

    private final Clock clock;

    public NotInClinicalFutureValidator() {
        this(Clock.systemDefaultZone());
    }

    NotInClinicalFutureValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return isAllowed(value, clock);
    }

    /** True when {@code value} is null or not in the future as seen from {@code clock}. */
    public static boolean isAllowed(Object value, Clock clock) {
        if (value == null) {
            return true;
        }
        Instant limit = clock.instant().plus(CLOCK_SKEW);
        if (value instanceof LocalDateTime ldt) {
            return !ldt.atZone(clock.getZone()).toInstant().isAfter(limit);
        }
        if (value instanceof OffsetDateTime odt) {
            return !odt.toInstant().isAfter(limit);
        }
        if (value instanceof ZonedDateTime zdt) {
            return !zdt.toInstant().isAfter(limit);
        }
        if (value instanceof Instant instant) {
            return !instant.isAfter(limit);
        }
        if (value instanceof LocalDate date) {
            return !date.isAfter(LocalDate.now(clock));
        }
        throw new UnexpectedTypeException("@NotInClinicalFuture does not support " + value.getClass().getName());
    }
}
