package com.example.hms.service.billing;

import com.example.hms.enums.PriceListPriceStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.PriceListPrice;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing-money plan PR 2: the whole-franc rule and the version rules, in French (the working language).
 * A version never starts in the past, a started version is never replaced, a planned one only with confirmation,
 * and the version in force is closed on the day before the new one starts.
 */
@DisplayName("Price list: price and version rules")
class PriceVersionRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final java.util.function.Predicate<PriceListPrice> ENTERED_EARLIER = v -> false;

    @BeforeEach
    void french() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static PriceListPrice version(String price, LocalDate from, LocalDate to) {
        PriceListPrice version = PriceListPrice.builder()
            .unitPrice(new BigDecimal(price)).effectiveFrom(from).effectiveTo(to).build();
        version.setId(UUID.randomUUID());
        return version;
    }

    @ParameterizedTest(name = "{0} F is refused naming unitPrice")
    @ValueSource(strings = {"2500.50", "0.5", "-1", "10000000000"})
    void aDecimalNegativeOrTooLargePriceIsRefused(String price) {
        assertThatThrownBy(() -> PriceVersionRules.requireValidPrice(new BigDecimal(price)))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("unitPrice"));
    }

    @Test
    void aMissingPriceIsRefusedInFrench() {
        assertThatThrownBy(() -> PriceVersionRules.requireValidPrice(null))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("unitPrice");
                assertThat(e.getMessage()).isEqualTo("Saisissez le prix unitaire.");
            });
    }

    @Test
    void aDecimalPriceSaysWholeFrancsInFrench() {
        assertThatThrownBy(() -> PriceVersionRules.requireValidPrice(new BigDecimal("2500.50")))
            .hasMessage("Saisissez le prix unitaire en francs CFA entiers, sans décimales.");
    }

    @ParameterizedTest(name = "{0} F is a valid price")
    @ValueSource(strings = {"0", "2000", "2000.00", "9999999999"})
    void wholeFrancsFromZeroAreAccepted(String price) {
        PriceVersionRules.requireValidPrice(new BigDecimal(price));
    }

    @Test
    void aStartInThePastIsRefusedAndNoStartMeansToday() {
        assertThatThrownBy(() -> PriceVersionRules.startOf(TODAY.minusDays(1), TODAY))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveFrom"));
        assertThat(PriceVersionRules.startOf(null, TODAY)).isEqualTo(TODAY);
        assertThat(PriceVersionRules.startOf(TODAY.plusDays(16), TODAY)).isEqualTo(TODAY.plusDays(16));
    }

    @Test
    void aFirstPriceHasNothingToClose() {
        PriceVersionRules.Plan plan = PriceVersionRules.plan(List.of(), TODAY, TODAY, false, ENTERED_EARLIER);
        assertThat(plan.toReplace()).isEmpty();
        assertThat(plan.predecessor()).isEmpty();
    }

    @Test
    void aFuturePriceClosesTheOneInForceTheDayBefore() {
        PriceListPrice inForce = version("2000", TODAY.minusDays(10), null);
        PriceVersionRules.Plan plan = PriceVersionRules.plan(List.of(inForce), TODAY, TODAY.plusDays(16), false, ENTERED_EARLIER);
        assertThat(plan.predecessor()).contains(inForce);
        assertThat(plan.toReplace()).isEmpty();
    }

    @Test
    void aVersionInForceSinceTodayButEnteredEarlierIsNeverReplaced() {
        PriceListPrice startedToday = version("2000", TODAY, null);
        assertThatThrownBy(() -> PriceVersionRules.plan(List.of(startedToday), TODAY, TODAY, true, ENTERED_EARLIER))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveFrom"));
    }

    @Test
    @DisplayName("decision 2026-11-16: a price entered today and in force since today is corrected only when confirmed")
    void aSameDayEntryIsCorrectedOnlyWhenConfirmed() {
        PriceListPrice previous = version("1500", TODAY.minusDays(30), TODAY.minusDays(1));
        PriceListPrice enteredToday = version("2000", TODAY, null);
        List<PriceListPrice> versions = List.of(enteredToday, previous);

        assertThatThrownBy(() -> PriceVersionRules.plan(versions, TODAY, TODAY, false, v -> v == enteredToday))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("replaceScheduled");
                assertThat(e.getMessage()).contains("2 000 F CFA");
            });

        PriceVersionRules.Plan plan = PriceVersionRules.plan(versions, TODAY, TODAY, true, v -> v == enteredToday);
        assertThat(plan.toReplace()).containsExactly(enteredToday);
        assertThat(plan.predecessor()).contains(previous);
    }

    @Test
    void aVersionThatStartedBeforeTodayIsClosedNeverReplacedEvenIfEnteredToday() {
        PriceListPrice started = version("2000", TODAY.minusDays(1), null);
        PriceVersionRules.Plan plan = PriceVersionRules.plan(List.of(started), TODAY, TODAY, false, v -> true);
        assertThat(plan.toReplace()).isEmpty();
        assertThat(plan.predecessor()).contains(started);
    }

    @Test
    void aPlannedVersionIsReplacedOnlyWhenConfirmed() {
        PriceListPrice inForce = version("2000", TODAY.minusDays(10), TODAY.plusDays(15));
        PriceListPrice planned = version("2500", TODAY.plusDays(16), null);
        List<PriceListPrice> versions = List.of(planned, inForce);

        assertThatThrownBy(() -> PriceVersionRules.plan(versions, TODAY, TODAY.plusDays(16), false, ENTERED_EARLIER))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("replaceScheduled");
                assertThat(e.getMessage()).contains("2 500 F CFA");
            });

        PriceVersionRules.Plan plan = PriceVersionRules.plan(versions, TODAY, TODAY.plusDays(16), true, ENTERED_EARLIER);
        assertThat(plan.toReplace()).containsExactly(planned);
        assertThat(plan.predecessor()).contains(inForce);
    }

    @Test
    @DisplayName("an earlier start never replaces a later planned version: the new one ends the day before it")
    void anEarlierStartKeepsTheLaterPlannedVersion() {
        PriceListPrice inForce = version("2000", TODAY.minusDays(10), TODAY.plusDays(15));
        PriceListPrice planned = version("2500", TODAY.plusDays(16), null);
        PriceVersionRules.Plan plan = PriceVersionRules.plan(List.of(planned, inForce), TODAY, TODAY.plusDays(5), false, ENTERED_EARLIER);
        assertThat(plan.toReplace()).isEmpty();
        assertThat(plan.predecessor()).contains(inForce);
        assertThat(plan.endsOn()).isEqualTo(TODAY.plusDays(15));
    }

    @Test
    @DisplayName("review 3b02ae1: with two planned versions only the one starting that day is replaced, and named")
    void withTwoPlannedVersionsOnlyTheNamedOneIsReplaced() {
        PriceListPrice inForce = version("2000", TODAY.minusDays(10), TODAY.plusDays(15));
        PriceListPrice b = version("2500", TODAY.plusDays(16), TODAY.plusDays(29));
        PriceListPrice c = version("3000", TODAY.plusDays(30), null);
        List<PriceListPrice> versions = List.of(c, b, inForce);

        // A new start between the two: nothing is replaced, no question, the new one ends before B.
        PriceVersionRules.Plan between = PriceVersionRules.plan(versions, TODAY, TODAY.plusDays(5), true, ENTERED_EARLIER);
        assertThat(between.toReplace()).isEmpty();
        assertThat(between.predecessor()).contains(inForce);
        assertThat(between.endsOn()).isEqualTo(TODAY.plusDays(15));

        // On B's day: the question names B (2 500), never C; confirmed, only B is replaced and C is kept.
        assertThatThrownBy(() -> PriceVersionRules.plan(versions, TODAY, TODAY.plusDays(16), false, ENTERED_EARLIER))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("replaceScheduled");
                assertThat(e.getMessage()).contains("2 500 F CFA").doesNotContain("3 000");
            });
        PriceVersionRules.Plan onB = PriceVersionRules.plan(versions, TODAY, TODAY.plusDays(16), true, ENTERED_EARLIER);
        assertThat(onB.toReplace()).containsExactly(b);
        assertThat(onB.predecessor()).contains(inForce);
        assertThat(onB.endsOn()).isEqualTo(TODAY.plusDays(29));
    }

    @Test
    @DisplayName("review 3b02ae1: a same-day correction replaces only the version of the day, never a planned one")
    void aSameDayCorrectionKeepsThePlannedVersion() {
        PriceListPrice enteredToday = version("2000", TODAY, TODAY.plusDays(15));
        PriceListPrice planned = version("2500", TODAY.plusDays(16), null);
        List<PriceListPrice> versions = List.of(planned, enteredToday);

        assertThatThrownBy(() -> PriceVersionRules.plan(versions, TODAY, TODAY, false, v -> v == enteredToday))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("replaceScheduled");
                assertThat(e.getMessage()).contains("2 000 F CFA").doesNotContain("2 500");
            });
        PriceVersionRules.Plan plan = PriceVersionRules.plan(versions, TODAY, TODAY, true, v -> v == enteredToday);
        assertThat(plan.toReplace()).containsExactly(enteredToday);
        assertThat(plan.endsOn()).isEqualTo(TODAY.plusDays(15));
    }

    @Test
    void aReplacedVersionIsIgnoredAndNeverInForce() {
        PriceListPrice replaced = version("2500", TODAY.plusDays(16), TODAY.plusDays(15));
        PriceListPrice inForce = version("2000", TODAY.minusDays(10), null);
        assertThat(replaced.isReplaced()).isTrue();
        assertThat(replaced.coversDay(TODAY.plusDays(16))).isFalse();
        PriceVersionRules.Plan plan = PriceVersionRules.plan(List.of(replaced, inForce), TODAY, TODAY.plusDays(16), false, ENTERED_EARLIER);
        assertThat(plan.toReplace()).isEmpty();
        assertThat(plan.predecessor()).contains(inForce);
    }

    @Test
    void theStatusFollowsTheBusinessDay() {
        assertThat(PriceVersionRules.statusOf(version("1", TODAY.plusDays(1), null), TODAY))
            .isEqualTo(PriceListPriceStatus.SCHEDULED);
        assertThat(PriceVersionRules.statusOf(version("1", TODAY, null), TODAY))
            .isEqualTo(PriceListPriceStatus.IN_FORCE);
        assertThat(PriceVersionRules.statusOf(version("1", TODAY.minusDays(9), TODAY.minusDays(1)), TODAY))
            .isEqualTo(PriceListPriceStatus.ENDED);
        assertThat(PriceVersionRules.statusOf(version("1", TODAY.plusDays(3), TODAY.plusDays(2)), TODAY))
            .isEqualTo(PriceListPriceStatus.REPLACED);
    }
}
