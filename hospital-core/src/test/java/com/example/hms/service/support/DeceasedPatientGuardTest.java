package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1488: the choke point. Each entry point's call is pinned by DeceasedPatientCareGuardTest. */
class DeceasedPatientGuardTest {

    private static final LocalDateTime DIED = LocalDateTime.of(2026, 3, 10, 14, 0);

    private Patient dead;
    private Patient living;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        dead = new Patient();
        dead.setDeceasedAt(DIED);
        living = new Patient();
    }

    @Test
    @DisplayName("new care on a dead patient is refused on the patient, in the reader's language, naming the death")
    void newCareIsRefusedNamingTheDeath() {
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseNewCare(dead))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                org.assertj.core.api.Assertions.assertThat(e.getField()).isEqualTo("patientId");
                org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("décédé").contains("2026");
            });
    }

    @Test
    @DisplayName("a living patient and no patient at all are not refused")
    void theLivingAndTheAnonymousPass() {
        assertThatCode(() -> DeceasedPatientGuard.refuseNewCare(living)).doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseNewCare(null)).doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeath(living, DIED.plusYears(1), "t"))
            .doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeathDay(null, LocalDate.now(), "t"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an observation after the death is refused on its time; one before it is late documentation")
    void theTimeOfAnObservationDecides() {
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseCareAfterDeath(dead, DIED.plusMinutes(1), "recordedAt"))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getField()).isEqualTo("recordedAt"));
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeath(dead, DIED, "recordedAt")).doesNotThrowAnyException();
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeath(dead, DIED.minusHours(3), "recordedAt"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no time on the observation means now, which is after the death")
    void noTimeMeansNow() {
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseCareAfterDeath(dead, null, "recordedAt"))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseCareAfterDeathDay(dead, null, "readingDate"))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("a reading dated by the day: the day of the death stays open, a later day is refused")
    void aDayOnlyReading() {
        assertThatCode(() -> DeceasedPatientGuard.refuseCareAfterDeathDay(dead, DIED.toLocalDate(), "readingDate"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> DeceasedPatientGuard.refuseCareAfterDeathDay(dead, DIED.toLocalDate().plusDays(1), "readingDate"))
            .isInstanceOf(FieldBusinessException.class);
    }
}
