package com.example.hms.service.support;

import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D1490. The perinatal bound here is NOT clinically approved: see the constant. */
class DeathClassificationRulesTest {

    private static final LocalDate BORN = LocalDate.of(2026, 3, 1);

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    private static Patient bornOn(LocalDate day) {
        Patient patient = new Patient();
        patient.setDateOfBirth(day);
        return patient;
    }

    private static Patient ofGender(String gender) {
        Patient patient = new Patient();
        patient.setGender(gender);
        return patient;
    }

    @Test
    @DisplayName("a death before the birth is refused on the date; the day of birth is possible")
    void deathBeforeBirth() {
        assertThatThrownBy(() -> DeathClassificationRules.requireDiedAfterBirth(bornOn(BORN), BORN.minusDays(1).atTime(23, 0)))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("diedAt"));
        assertThatCode(() -> DeathClassificationRules.requireDiedAfterBirth(bornOn(BORN), BORN.atStartOfDay()))
            .doesNotThrowAnyException();
        assertThatCode(() -> DeathClassificationRules.requireDiedAfterBirth(new Patient(), BORN.atStartOfDay()))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"M", "male", "Homme", "masculin"})
    @DisplayName("maternal on a patient recorded male is refused on the classification, and says the death can be recorded")
    void maternalOnAMale(String gender) {
        assertThatThrownBy(() -> DeathClassificationRules.requireMaternalPossible(ofGender(gender)))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("maternalDeath");
                assertThat(e.getMessage()).contains("can be recorded without");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"F", "Femme", "", "UNKNOWN"})
    @DisplayName("female and unknown sex keep the classification")
    void maternalOnOthers(String gender) {
        assertThatCode(() -> DeathClassificationRules.requireMaternalPossible(ofGender(gender))).doesNotThrowAnyException();
        assertThatCode(() -> DeathClassificationRules.requireMaternalPossible(new Patient())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the perinatal period ends at seven completed days: day 6 is in, day 7 is out")
    void theSevenDayBoundary() {
        LocalDateTime day6 = BORN.plusDays(6).atTime(23, 59);
        LocalDateTime day7 = BORN.plusDays(7).atStartOfDay();
        for (PerinatalDeathType type : new PerinatalDeathType[] {PerinatalDeathType.STILLBIRTH, PerinatalDeathType.EARLY_NEONATAL}) {
            assertThatCode(() -> DeathClassificationRules.requirePerinatalPossible(bornOn(BORN), type, day6))
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> DeathClassificationRules.requirePerinatalPossible(bornOn(BORN), type, day7))
                .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("perinatalType");
                    assertThat(e.getMessage()).contains("can be recorded without");
                });
        }
    }

    @Test
    @DisplayName("a stillbirth on an adult's chart, the mother's, is refused: the record would mark her dead")
    void stillbirthOnTheMothersChart() {
        Patient mother = bornOn(LocalDate.of(1990, 1, 1));
        assertThatThrownBy(() -> DeathClassificationRules.requirePerinatalPossible(
                mother, PerinatalDeathType.STILLBIRTH, BORN.atStartOfDay()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getMessage()).contains("delivery record"));
    }

    @Test
    @DisplayName("LATE_NEONATAL keeps its own definition, 7 to 28 days, or the type could never be used")
    void lateNeonatalKeepsItsDefinition() {
        assertThatCode(() -> DeathClassificationRules.requirePerinatalPossible(
                bornOn(BORN), PerinatalDeathType.LATE_NEONATAL, BORN.plusDays(27).atStartOfDay()))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> DeathClassificationRules.requirePerinatalPossible(
                bornOn(BORN), PerinatalDeathType.LATE_NEONATAL, BORN.plusDays(28).atStartOfDay()))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("no birth date refuses nothing")
    void noBirthDate() {
        assertThatCode(() -> DeathClassificationRules.requirePerinatalPossible(
                new Patient(), PerinatalDeathType.STILLBIRTH, BORN.atStartOfDay()))
            .doesNotThrowAnyException();
    }
}
