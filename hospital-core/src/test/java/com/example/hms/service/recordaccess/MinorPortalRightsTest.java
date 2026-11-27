package com.example.hms.service.recordaccess;

import com.example.hms.service.recordaccess.MinorPortalRights.Action;
import com.example.hms.service.recordaccess.MinorPortalRights.Band;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QA D4269: what a patient may do alone on their own portal account, by age")
class MinorPortalRightsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void theBandFollowsTheDateOfBirthToTheDay() {
        assertThat(MinorPortalRights.bandOf(LocalDate.of(2017, 9, 2), TODAY)).isEqualTo(Band.UNDER_13);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(13).plusDays(1), TODAY)).isEqualTo(Band.UNDER_13);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(13), TODAY)).isEqualTo(Band.AGE_13_14);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(15).plusDays(1), TODAY)).isEqualTo(Band.AGE_13_14);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(15), TODAY)).isEqualTo(Band.AGE_15_17);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(18).plusDays(1), TODAY)).isEqualTo(Band.AGE_15_17);
        assertThat(MinorPortalRights.bandOf(TODAY.minusYears(18), TODAY)).isEqualTo(Band.ADULT);
        // No date of birth on record is read as an adult (ProxySensitivityPolicy reads it the same way).
        assertThat(MinorPortalRights.bandOf(null, TODAY)).isEqualTo(Band.ADULT);
    }

    @Test
    void eachBandIsAllowedExactlyItsActions() {
        assertThat(allowed(Band.UNDER_13)).isEmpty();
        assertThat(allowed(Band.AGE_13_14)).containsExactly(Action.ACTIVATE_ACCOUNT);
        assertThat(allowed(Band.AGE_15_17)).containsExactlyInAnyOrder(Action.ACTIVATE_ACCOUNT,
            Action.REVOKE_FAMILY_ACCESS, Action.CLOSE_SHARING, Action.WITHDRAW_CONTACT_CONSENT);
        assertThat(allowed(Band.ADULT)).containsExactlyInAnyOrder(Action.values());
    }

    @Test
    void everyRefusalHasItsOwnWordsAndAPermittedActionHasNone() {
        for (Band band : Band.values()) {
            for (Action action : Action.values()) {
                String key = MinorPortalRights.refusalKey(band, action);
                if (MinorPortalRights.permits(band, action)) {
                    assertThat(key).as("%s %s", band, action).isNull();
                } else {
                    assertThat(key).as("%s %s", band, action).startsWith("portal.minor.");
                }
            }
        }
        assertThat(MinorPortalRights.refusalKey(Band.UNDER_13, Action.ACTIVATE_ACCOUNT))
            .isEqualTo("portal.minor.noAccountUnder13");
        assertThat(MinorPortalRights.refusalKey(Band.AGE_13_14, Action.CLOSE_SHARING)).isEqualTo("portal.minor.readOnly");
        assertThat(MinorPortalRights.refusalKey(Band.AGE_15_17, Action.GRANT_FAMILY_ACCESS))
            .isEqualTo("portal.minor.grantNeedsGuardian");
        assertThat(MinorPortalRights.refusalKey(Band.AGE_15_17, Action.REOPEN_SHARING))
            .isEqualTo("portal.minor.sharingOnNeedsGuardian");
        assertThat(MinorPortalRights.refusalKey(Band.AGE_15_17, Action.GIVE_CONTACT_CONSENT))
            .isEqualTo("portal.minor.consentNeedsGuardian");
        assertThat(MinorPortalRights.refusalKey(Band.AGE_15_17, Action.REVOKE_VERIFIED_GUARDIAN_ACCESS))
            .isEqualTo("portal.minor.verifiedGuardianAccess");
    }

    @Test
    void theScreensAreToldTheSameAnswers() {
        MinorPortalRights.Rights sixteen = MinorPortalRights.rightsOf(TODAY.minusYears(16), TODAY);
        assertThat(sixteen.band()).isEqualTo(Band.AGE_15_17);
        assertThat(sixteen.grantFamilyAccess()).isFalse();
        assertThat(sixteen.revokeFamilyAccess()).isTrue();
        assertThat(sixteen.closeSharing()).isTrue();
        assertThat(sixteen.reopenSharing()).isFalse();
        assertThat(sixteen.withdrawContactConsent()).isTrue();
        assertThat(sixteen.giveContactConsent()).isFalse();
        MinorPortalRights.Rights nine = MinorPortalRights.rightsOf(LocalDate.of(2017, 9, 2), TODAY);
        assertThat(nine.closeSharing()).isFalse();
        assertThat(nine.grantFamilyAccess()).isFalse();
    }

    @Test
    void anAccessGivenDuringMinorityLapsesOnThe18thBirthdayAndALaterOneDoesNot() {
        LocalDate born = LocalDate.of(2008, 11, 27);
        LocalDateTime givenAt16 = LocalDateTime.of(2025, 1, 10, 9, 0);
        assertThat(MinorPortalRights.lapsedAtMajority(born, givenAt16, TODAY.minusDays(1))).isFalse();
        assertThat(MinorPortalRights.lapsedAtMajority(born, givenAt16, TODAY)).isTrue();
        assertThat(MinorPortalRights.lapsedAtMajority(born, TODAY.atTime(8, 0), TODAY.plusDays(3))).isFalse();
        assertThat(MinorPortalRights.lapsedAtMajority(null, givenAt16, TODAY)).isFalse();
        assertThat(MinorPortalRights.lapsedAtMajority(born, null, TODAY)).isFalse();
    }

    @Test
    void everyRefusalIsWordedInEveryLanguage() {
        var bundles = com.example.hms.i18n.TestMessageSources.bundles();
        Set<String> keys = new java.util.TreeSet<>();
        for (Band band : Band.values()) {
            for (Action action : Action.values()) {
                String key = MinorPortalRights.refusalKey(band, action);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        assertThat(keys).hasSize(7);
        for (String key : keys) {
            for (String language : new String[]{"fr", "en", "es"}) {
                String text = bundles.getMessage(key, null, null, java.util.Locale.forLanguageTag(language));
                assertThat(text).as("%s in %s", key, language).isNotBlank();
            }
        }
        assertThat(bundles.getMessage("portal.minor.grantNeedsGuardian", null, java.util.Locale.FRENCH))
            .contains("parent", "tuteur", "accueil");
    }

    private static Set<Action> allowed(Band band) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) {
            if (MinorPortalRights.permits(band, action)) {
                out.add(action);
            }
        }
        return out;
    }
}
