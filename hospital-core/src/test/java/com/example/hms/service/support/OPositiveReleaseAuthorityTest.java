package com.example.hms.service.support;

import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.OPositiveTierReason;
import com.example.hms.enums.RhFactor;
import com.example.hms.service.support.OPositiveReleaseAuthority.Settings;
import com.example.hms.service.support.OPositiveReleaseAuthority.Sex;
import com.example.hms.service.support.OPositiveReleaseAuthority.Subject;
import com.example.hms.service.support.OPositiveReleaseAuthority.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The decision table of the owner decision of 2026-11-25, row by row. */
@DisplayName("who decides an emergency O RhD positive release, by patient")
class OPositiveReleaseAuthorityTest {

    private static final Settings DEFAULTS = new Settings(true, 50, false, 24);
    private static final Settings BOYS_LOW = new Settings(true, 50, true, 24);

    private static Tier tier(Sex sex, Integer age, boolean antibody, Settings settings) {
        return OPositiveReleaseAuthority.tierOf(new Subject(sex, age, null, antibody), settings);
    }

    @Test
    @DisplayName("row 1: a man of 18 or more, or a woman above the cut-off, under the active protocol: low risk")
    void lowRisk() {
        for (int age : new int[] {18, 19, 40, 90}) {
            assertThat(tier(Sex.MALE, age, false, DEFAULTS)).as("man of %d", age)
                .isEqualTo(new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.ADULT_MALE));
        }
        for (int age : new int[] {51, 60, 90}) {
            assertThat(tier(Sex.FEMALE, age, false, DEFAULTS)).as("woman of %d", age)
                .isEqualTo(new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.FEMALE_ABOVE_CUTOFF));
        }
    }

    @Test
    @DisplayName("row 2: a woman at or below the cut-off, a girl, a boy, an unknown sex or age: high risk")
    void highRisk() {
        for (int age : new int[] {0, 5, 17, 18, 30, 50}) {
            assertThat(tier(Sex.FEMALE, age, false, DEFAULTS)).as("female of %d", age)
                .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.FEMALE_AT_OR_BELOW_CUTOFF));
        }
        for (int age : new int[] {0, 5, 17}) {
            assertThat(tier(Sex.MALE, age, false, DEFAULTS)).as("boy of %d", age)
                .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.MALE_UNDER_18));
        }
        assertThat(tier(Sex.UNKNOWN, 40, false, DEFAULTS))
            .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.UNKNOWN_SEX));
        assertThat(tier(Sex.MALE, null, false, DEFAULTS))
            .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.UNKNOWN_AGE));
        assertThat(tier(Sex.FEMALE, null, false, DEFAULTS))
            .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.UNKNOWN_AGE));
    }

    @Test
    @DisplayName("the cut-off is the hospital's: 45 moves a woman of 46 to low risk, 55 keeps a woman of 55 protected")
    void theCutoffIsASetting() {
        assertThat(tier(Sex.FEMALE, 46, false, new Settings(true, 45, false, 24)).tier()).isEqualTo(OPositiveRiskTier.LOW_RISK);
        assertThat(tier(Sex.FEMALE, 45, false, new Settings(true, 45, false, 24)).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(tier(Sex.FEMALE, 55, false, new Settings(true, 55, false, 24)).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(tier(Sex.FEMALE, 56, false, new Settings(true, 55, false, 24)).tier()).isEqualTo(OPositiveRiskTier.LOW_RISK);
    }

    @Test
    @DisplayName("boys are high risk by default; the hospital may place them in the first row, never girls or an unknown age")
    void boysAreASetting() {
        assertThat(tier(Sex.MALE, 10, false, BOYS_LOW))
            .isEqualTo(new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.BOY_LOW_RISK_BY_PROTOCOL));
        assertThat(tier(Sex.FEMALE, 10, false, BOYS_LOW).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(tier(Sex.MALE, null, false, BOYS_LOW).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        assertThat(tier(Sex.UNKNOWN, 10, false, BOYS_LOW).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
    }

    @Test
    @DisplayName("row 3: a known antibody comes first, whoever the patient and whatever the protocol")
    void knownAntibody() {
        for (Settings settings : new Settings[] {DEFAULTS, BOYS_LOW, Settings.NO_PROTOCOL}) {
            for (Sex sex : Sex.values()) {
                assertThat(tier(sex, 40, true, settings))
                    .isEqualTo(new Tier(OPositiveRiskTier.KNOWN_ANTIBODY, OPositiveTierReason.KNOWN_ANTIBODY));
            }
        }
    }

    @Test
    @DisplayName("row 4: with no active protocol every patient is the high-risk row, an adult man included")
    void noProtocol() {
        for (Settings settings : new Settings[] {Settings.NO_PROTOCOL, new Settings(false, 50, true, 24)}) {
            assertThat(tier(Sex.MALE, 40, false, settings))
                .isEqualTo(new Tier(OPositiveRiskTier.HIGH_RISK, OPositiveTierReason.NO_ACTIVE_PROTOCOL));
            assertThat(tier(Sex.FEMALE, 70, false, settings).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
            assertThat(tier(Sex.MALE, 10, false, settings).tier()).isEqualTo(OPositiveRiskTier.HIGH_RISK);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "null", value = {
        "MALE,MALE", "m,MALE", "homme,MALE", "Masculin,MALE", "' male ',MALE",
        "FEMALE,FEMALE", "f,FEMALE", "femme,FEMALE", "féminin,FEMALE",
        "OTHER,UNKNOWN", "x,UNKNOWN", "'',UNKNOWN", "null,UNKNOWN",
    })
    @DisplayName("the sex is what the record proves; anything else is unknown")
    void sex(String gender, Sex expected) {
        assertThat(OPositiveReleaseAuthority.sexOf(gender)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the age is in completed years; a missing or future date of birth proves none")
    void age() {
        LocalDate today = LocalDate.of(2026, 11, 25);
        assertThat(OPositiveReleaseAuthority.ageYears(today.minusYears(18), today)).isEqualTo(18);
        assertThat(OPositiveReleaseAuthority.ageYears(today.minusYears(18).plusDays(1), today)).isEqualTo(17);
        assertThat(OPositiveReleaseAuthority.ageYears(null, today)).isNull();
        assertThat(OPositiveReleaseAuthority.ageYears(today.plusDays(1), today)).isNull();
    }

    @Test
    @DisplayName("the plain issue is the low-risk tier's, except for a patient typed RhD negative")
    void plainIssue() {
        Tier low = new Tier(OPositiveRiskTier.LOW_RISK, OPositiveTierReason.ADULT_MALE);
        assertThat(OPositiveReleaseAuthority.plainIssueAllowed(low, null)).isTrue();
        assertThat(OPositiveReleaseAuthority.plainIssueAllowed(low, RhFactor.POSITIVE)).isTrue();
        assertThat(OPositiveReleaseAuthority.plainIssueAllowed(low, RhFactor.NEGATIVE)).isFalse();
        for (OPositiveRiskTier tier : new OPositiveRiskTier[] {OPositiveRiskTier.HIGH_RISK, OPositiveRiskTier.KNOWN_ANTIBODY}) {
            assertThat(OPositiveReleaseAuthority.plainIssueAllowed(new Tier(tier, OPositiveTierReason.UNKNOWN_SEX), null)).isFalse();
        }
    }

    @Test
    @DisplayName("the review is routine, urgent or immediate by tier; the exposure is followed outside the low-risk tier")
    void whatFollows() {
        assertThat(OPositiveReleaseAuthority.reviewPriority(OPositiveRiskTier.LOW_RISK)).isEqualTo(OPositiveReviewPriority.ROUTINE);
        assertThat(OPositiveReleaseAuthority.reviewPriority(OPositiveRiskTier.HIGH_RISK)).isEqualTo(OPositiveReviewPriority.URGENT);
        assertThat(OPositiveReleaseAuthority.reviewPriority(OPositiveRiskTier.KNOWN_ANTIBODY)).isEqualTo(OPositiveReviewPriority.IMMEDIATE);
        assertThat(OPositiveReleaseAuthority.exposureFollowUp(OPositiveRiskTier.LOW_RISK, null)).isFalse();
        assertThat(OPositiveReleaseAuthority.exposureFollowUp(OPositiveRiskTier.HIGH_RISK, null)).isTrue();
        assertThat(OPositiveReleaseAuthority.exposureFollowUp(OPositiveRiskTier.HIGH_RISK, RhFactor.NEGATIVE)).isTrue();
        assertThat(OPositiveReleaseAuthority.exposureFollowUp(OPositiveRiskTier.KNOWN_ANTIBODY, null)).isTrue();
        // Typed RhD positive by the laboratory: O positive is no RhD exposure, and no flag states one.
        assertThat(OPositiveReleaseAuthority.exposureFollowUp(OPositiveRiskTier.HIGH_RISK, RhFactor.POSITIVE)).isFalse();
    }

    @Test
    @DisplayName("what was written on the release decides its state; the bag is read only while nothing is written")
    void whatIsWrittenOnTheReleaseWins() {
        var given = com.example.hms.enums.OPositiveBagDisposition.ADMINISTERED;
        var back = com.example.hms.enums.OPositiveBagDisposition.RETURNED_UNUSED;
        var again = com.example.hms.enums.OPositiveBagDisposition.SUPERSEDED;
        assertThat(OPositiveReleaseAuthority.exposureState(given, false, false)).isEqualTo(OPositiveReleaseAuthority.ExposureState.CONFIRMED);
        // Closed as not given: a later hang of the same bag, on the same request even, does not revive it.
        assertThat(OPositiveReleaseAuthority.exposureState(back, true, true)).isEqualTo(OPositiveReleaseAuthority.ExposureState.NOT_GIVEN);
        assertThat(OPositiveReleaseAuthority.exposureState(again, true, true)).isEqualTo(OPositiveReleaseAuthority.ExposureState.NOT_GIVEN);
        assertThat(OPositiveReleaseAuthority.exposureState(null, true, false)).isEqualTo(OPositiveReleaseAuthority.ExposureState.CONFIRMED);
        assertThat(OPositiveReleaseAuthority.exposureState(null, false, true)).isEqualTo(OPositiveReleaseAuthority.ExposureState.UNCONFIRMED);
        assertThat(OPositiveReleaseAuthority.exposureState(null, false, false)).isEqualTo(OPositiveReleaseAuthority.ExposureState.NOT_GIVEN);
    }
}
