package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D253: an imaging exam could be scheduled for a day that had passed. */
class ImagingOrderScheduledDateTest {

    @Test
    void aPastDateIsRefused() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        assertThatThrownBy(() -> ImagingOrderServiceImpl.requireNotInThePast(yesterday, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void todayAndLaterAreAccepted() {
        assertThatCode(() -> ImagingOrderServiceImpl.requireNotInThePast(LocalDate.now(), null)).doesNotThrowAnyException();
        assertThatCode(() -> ImagingOrderServiceImpl.requireNotInThePast(null, null)).doesNotThrowAnyException();
    }

    @Test
    void theDateTheOrderAlreadyCarriesIsLeftAlone() {
        LocalDate past = LocalDate.now().minusDays(3);
        assertThatCode(() -> ImagingOrderServiceImpl.requireNotInThePast(past, past)).doesNotThrowAnyException();
    }
}
