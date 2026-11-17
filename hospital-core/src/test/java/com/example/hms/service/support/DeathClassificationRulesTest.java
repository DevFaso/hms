package com.example.hms.service.support;

import com.example.hms.enums.PerinatalAgeBasis;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Patient;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

/** QA D1490, CR-07, CR-08. The bounds here are NOT clinically approved: see the constants. */
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

    private static PerinatalAgeBasis judged(LocalDate bornOn, PerinatalDeathType type, LocalDateTime diedAt) {
        return DeathClassificationRules.requirePerinatalPossible(
            bornOn(bornOn), type, diedAt, null, PerinatalBirthStatus.UNKNOWN);
    }

    private static PerinatalAgeBasis judgedByTime(PerinatalDeathType type, LocalDateTime bornAt, LocalDateTime diedAt) {
        return DeathClassificationRules.requirePerinatalPossible(
            bornOn(bornAt.toLocalDate()), type, diedAt, bornAt, PerinatalBirthStatus.LIVE_BIRTH);
    }

    private static void assertRefusedOnTheType(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FieldBusinessException.class, e -> {
            assertThat(e.getField()).isEqualTo("perinatalType");
            assertThat(e.getMessage()).contains("can be recorded without");
        });
    }

    // ── Dates only: the birth could be at any hour of that day ──────────

    @Test
    @DisplayName("dates only: under seven is early, past the boundary is not, and day 7 cannot be decided so is kept")
    void theSevenDayBoundaryOnDates() {
        for (PerinatalDeathType type : new PerinatalDeathType[] {PerinatalDeathType.STILLBIRTH, PerinatalDeathType.EARLY_NEONATAL}) {
            assertThat(judged(BORN, type, BORN.plusDays(6).atTime(23, 59))).isEqualTo(PerinatalAgeBasis.DATE_ONLY);
            assertThat(judged(BORN, type, BORN.plusDays(7).atStartOfDay())).isEqualTo(PerinatalAgeBasis.DATE_ONLY_BOUNDARY);
            assertRefusedOnTheType(() -> judged(BORN, type, BORN.plusDays(8).atStartOfDay()));
        }
    }

    @Test
    @DisplayName("dates only: late neonatal has a lower bound, and days 7 and 28 cannot be decided so are kept")
    void lateNeonatalBoundsOnDates() {
        PerinatalDeathType late = PerinatalDeathType.LATE_NEONATAL;
        assertRefusedOnTheType(() -> judged(BORN, late, BORN.plusDays(2).atTime(12, 0)));
        assertRefusedOnTheType(() -> judged(BORN, late, BORN.plusDays(6).atTime(23, 59)));
        assertThat(judged(BORN, late, BORN.plusDays(7).atStartOfDay())).isEqualTo(PerinatalAgeBasis.DATE_ONLY_BOUNDARY);
        assertThat(judged(BORN, late, BORN.plusDays(8).atStartOfDay())).isEqualTo(PerinatalAgeBasis.DATE_ONLY);
        assertThat(judged(BORN, late, BORN.plusDays(27).atStartOfDay())).isEqualTo(PerinatalAgeBasis.DATE_ONLY);
        assertThat(judged(BORN, late, BORN.plusDays(28).atStartOfDay())).isEqualTo(PerinatalAgeBasis.DATE_ONLY_BOUNDARY);
        assertRefusedOnTheType(() -> judged(BORN, late, BORN.plusDays(29).atStartOfDay()));
    }

    // ── A birth time: completed days are known ──────────────────────────

    @Test
    @DisplayName("birth 1 Nov 23:50 and death 8 Nov 00:10 is under seven completed days, whatever the dates subtract to")
    void theReportedPair() {
        LocalDateTime bornAt = LocalDateTime.of(2025, 11, 1, 23, 50);
        LocalDateTime diedAt = LocalDateTime.of(2025, 11, 8, 0, 10);

        assertThat(judgedByTime(PerinatalDeathType.EARLY_NEONATAL, bornAt, diedAt)).isEqualTo(PerinatalAgeBasis.ELAPSED_TIME);
        assertRefusedOnTheType(() -> judgedByTime(PerinatalDeathType.LATE_NEONATAL, bornAt, diedAt));
    }

    @Test
    @DisplayName("elapsed time: a minute before and at seven completed days")
    void sevenCompletedDays() {
        LocalDateTime bornAt = LocalDateTime.of(2025, 11, 1, 10, 0);
        LocalDateTime justBefore = bornAt.plusDays(7).minusMinutes(1);
        LocalDateTime at = bornAt.plusDays(7);

        assertThat(judgedByTime(PerinatalDeathType.EARLY_NEONATAL, bornAt, justBefore)).isEqualTo(PerinatalAgeBasis.ELAPSED_TIME);
        assertRefusedOnTheType(() -> judgedByTime(PerinatalDeathType.LATE_NEONATAL, bornAt, justBefore));
        assertRefusedOnTheType(() -> judgedByTime(PerinatalDeathType.EARLY_NEONATAL, bornAt, at));
        assertThat(judgedByTime(PerinatalDeathType.LATE_NEONATAL, bornAt, at)).isEqualTo(PerinatalAgeBasis.ELAPSED_TIME);
    }

    @Test
    @DisplayName("elapsed time: a minute before and at 28 completed days")
    void twentyEightCompletedDays() {
        LocalDateTime bornAt = LocalDateTime.of(2025, 11, 1, 10, 0);

        assertThat(judgedByTime(PerinatalDeathType.LATE_NEONATAL, bornAt, bornAt.plusDays(28).minusMinutes(1)))
            .isEqualTo(PerinatalAgeBasis.ELAPSED_TIME);
        assertRefusedOnTheType(() -> judgedByTime(PerinatalDeathType.LATE_NEONATAL, bornAt, bornAt.plusDays(28)));
    }

    // ── Live birth or stillbirth ────────────────────────────────────────

    @Test
    @DisplayName("a stated birth status that contradicts the type refuses the type; an unknown one refuses nothing")
    void birthStatusAgainstType() {
        LocalDateTime day2 = BORN.plusDays(2).atStartOfDay();
        assertRefusedOnTheType(() -> DeathClassificationRules.requirePerinatalPossible(
            bornOn(BORN), PerinatalDeathType.STILLBIRTH, day2, null, PerinatalBirthStatus.LIVE_BIRTH));
        for (PerinatalDeathType neonatal : new PerinatalDeathType[] {PerinatalDeathType.EARLY_NEONATAL, PerinatalDeathType.LATE_NEONATAL}) {
            assertRefusedOnTheType(() -> DeathClassificationRules.requirePerinatalPossible(
                bornOn(BORN), neonatal, BORN.plusDays(10).atStartOfDay(), null, PerinatalBirthStatus.STILLBIRTH));
        }
        assertThatCode(() -> DeathClassificationRules.requirePerinatalPossible(
            bornOn(BORN), PerinatalDeathType.STILLBIRTH, day2, null, PerinatalBirthStatus.STILLBIRTH)).doesNotThrowAnyException();
        assertThatCode(() -> DeathClassificationRules.requirePerinatalPossible(
            bornOn(BORN), PerinatalDeathType.EARLY_NEONATAL, day2, null, PerinatalBirthStatus.LIVE_BIRTH)).doesNotThrowAnyException();
        assertThatCode(() -> judged(BORN, PerinatalDeathType.EARLY_NEONATAL, day2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a stillbirth on an adult's chart, the mother's, is refused: the record would mark her dead")
    void stillbirthOnTheMothersChart() {
        assertThatThrownBy(() -> judged(LocalDate.of(1990, 1, 1), PerinatalDeathType.STILLBIRTH, BORN.atStartOfDay()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getMessage()).contains("delivery record"));
    }

    @Test
    @DisplayName("no date of birth refuses nothing, and comes back unknown rather than as a pass")
    void noBirthDate() {
        for (PerinatalDeathType type : PerinatalDeathType.values()) {
            assertThat(DeathClassificationRules.requirePerinatalPossible(
                new Patient(), type, BORN.atStartOfDay(), null, PerinatalBirthStatus.UNKNOWN))
                .isEqualTo(PerinatalAgeBasis.UNKNOWN);
        }
    }

    // ── The facts offered for it ────────────────────────────────────────

    @Test
    @DisplayName("gestation out of range is refused on its field, a known 18 weeks is recordable, and empty refuses nothing")
    void gestationRange() {
        for (int weeks : new int[] {0, 46}) {
            assertThatThrownBy(() -> DeathClassificationRules.requireBirthFactsPossible(
                bornOn(BORN), null, weeks, BORN.atStartOfDay()))
                .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("gestationalAgeWeeks");
                    assertThat(e.getMessage()).contains("can be recorded without");
                });
        }
        for (Integer weeks : new Integer[] {null, 1, 18, 21, 22, 45}) {
            assertThatCode(() -> DeathClassificationRules.requireBirthFactsPossible(
                bornOn(BORN), null, weeks, BORN.atStartOfDay())).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a request's gestation has no bean-validation bound, which would refuse the death itself")
    void theRequestItselfRefusesNoGestation() {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validateValue(
                com.example.hms.payload.dto.mortality.DeathRecordRequestDTO.class, "gestationalAgeWeeks", 99)).isEmpty();
        }
    }

    @Test
    @DisplayName("a birth time after the death or far from the date of birth is refused on its field")
    void birthTimeThatCannotBe() {
        LocalDateTime died = BORN.plusDays(10).atStartOfDay();
        for (LocalDateTime wrong : new LocalDateTime[] {died.plusMinutes(1), BORN.plusDays(2).atStartOfDay(), BORN.minusDays(2).atStartOfDay()}) {
            assertThatThrownBy(() -> DeathClassificationRules.requireBirthFactsPossible(bornOn(BORN), wrong, null, died))
                .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("bornAt");
                    assertThat(e.getMessage()).contains("can be recorded without");
                });
        }
        assertThatCode(() -> DeathClassificationRules.requireBirthFactsPossible(bornOn(BORN), BORN.atTime(8, 0), null, died))
            .doesNotThrowAnyException();
        assertThatCode(() -> DeathClassificationRules.requireBirthFactsPossible(new Patient(), BORN.atTime(8, 0), null, died))
            .doesNotThrowAnyException();
    }
}
