package com.example.hms.exception;

import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service handed its caller's locale explicitly (StaffSchedulingServiceImpl)
 * must render the not-found in THAT locale, not the thread's.
 */
class ResourceNotFoundExceptionLocaleTest {

    private final MessageSource previous =
        (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @AfterEach
    void restore() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void inLocaleRendersInTheGivenLocaleNotTheThreads() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("schedule.shift.notFound", Locale.ENGLISH, "Shift not found.");
        source.addMessage("schedule.shift.notFound", Locale.FRENCH, "Garde introuvable.");
        MessageUtil.setMessageSource(source);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        ResourceNotFoundException ex = ResourceNotFoundException.inLocale(Locale.FRENCH, "schedule.shift.notFound");

        assertThat(ex.getMessage()).isEqualTo("Garde introuvable.");
        assertThat(ex.getMessageKey()).isEqualTo("schedule.shift.notFound");
    }
}
