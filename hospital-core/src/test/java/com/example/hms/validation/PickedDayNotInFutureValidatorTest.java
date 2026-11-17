package com.example.hms.validation;

import com.example.hms.payload.dto.StaffRequestDTO;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #948 review: the portal now defaults a staff start date (and a goods
 * receipt date) to the READER's today. At 23:30Z a hospital at UTC+1 is
 * already on the next day, which {@code @PastOrPresent} on the UTC server
 * refused, so the untouched form could not be saved.
 */
class PickedDayNotInFutureValidatorTest {

    /** 23:30Z on 24 October: 00:30 on the 25th at UTC+1. */
    private static final Clock LATE_UTC = Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneOffset.UTC);

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        ConstraintValidatorFactory defaults =
            Validation.byDefaultProvider().configure().getDefaultConstraintValidatorFactory();
        ConstraintValidatorFactory pinned = new ConstraintValidatorFactory() {
            @Override
            public <V extends ConstraintValidator<?, ?>> V getInstance(Class<V> key) {
                return key == PickedDayNotInFutureValidator.class
                    ? key.cast(new PickedDayNotInFutureValidator(LATE_UTC))
                    : defaults.getInstance(key);
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
                defaults.releaseInstance(instance);
            }
        };
        factory = Validation.byDefaultProvider().configure().constraintValidatorFactory(pinned)
            .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void readersTodayEastOfUtcIsAllowed() {
        assertThat(PickedDayNotInFutureValidator.isAllowed(LocalDate.of(2026, 10, 25), LATE_UTC)).isTrue();
        assertThat(PickedDayNotInFutureValidator.isAllowed(LocalDate.of(2026, 10, 24), LATE_UTC)).isTrue();
        assertThat(PickedDayNotInFutureValidator.isAllowed(null, LATE_UTC)).isTrue();
    }

    @Test
    void aDayBeyondTheGraceIsRefused() {
        assertThat(PickedDayNotInFutureValidator.isAllowed(LocalDate.of(2026, 10, 26), LATE_UTC)).isFalse();
    }

    @Test
    void theRuleReadsTheUtcCalendarWhateverTheClockZone() {
        // The same instant on a clock in a zone already on the 25th: the limit stays the 25th.
        Clock eastern = LATE_UTC.withZone(ZoneId.of("Pacific/Kiritimati"));
        assertThat(PickedDayNotInFutureValidator.isAllowed(LocalDate.of(2026, 10, 25), eastern)).isTrue();
        assertThat(PickedDayNotInFutureValidator.isAllowed(LocalDate.of(2026, 10, 26), eastern)).isFalse();
    }

    @Test
    void staffStartDateDefaultedToTheReadersTodayPassesValidation() {
        StaffRequestDTO dto = StaffRequestDTO.builder().startDate(LocalDate.of(2026, 10, 25)).build();

        assertThat(validator.validateProperty(dto, "startDate")).isEmpty();
    }

    @Test
    void staffStartDateBeyondTheGraceIsRefused() {
        StaffRequestDTO dto = StaffRequestDTO.builder().startDate(LocalDate.of(2026, 10, 26)).build();

        assertThat(validator.validateProperty(dto, "startDate"))
            .singleElement()
            .satisfies(v -> assertThat(v.getConstraintDescriptor().getAnnotation())
                .isInstanceOf(PickedDayNotInFuture.class));
    }
}
