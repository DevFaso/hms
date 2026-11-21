package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA r65 D2500 / D2501: the hospital's code, currency and time zone, and whose zone decides its day. */
@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class HospitalLocaleRulesTest {

    @Test
    @DisplayName("a code is stored trimmed and in capitals")
    void codeIsNormalised() {
        assertThat(HospitalLocaleRules.requireCode("  twp ")).isEqualTo("TWP");
        assertThat(HospitalLocaleRules.requireCode("cmt-c5f20")).isEqualTo("CMT-C5F20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "-AB", "AB-", "A B", "TWP/1", "ÉCOLE", "ABCDEFGHIJKLMNOPQRSTU"})
    @DisplayName("a code with a space, punctuation, an accent, one character or more than 20 is refused on its field")
    void badCodeIsRefused(String raw) {
        assertThatThrownBy(() -> HospitalLocaleRules.requireCode(raw))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("code"));
    }

    @Test
    @DisplayName("the CFA francs are accepted, in any case")
    void cfaFrancsAreAccepted() {
        assertThat(HospitalLocaleRules.requireCurrency("xof")).isEqualTo("XOF");
        assertThat(HospitalLocaleRules.requireCurrency(" XAF ")).isEqualTo("XAF");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "USD", "GNF", "CFA", "", "XO"})
    @DisplayName("a currency the server would print as « F CFA » wrongly is refused on its field")
    void otherCurrencyIsRefused(String raw) {
        assertThatThrownBy(() -> HospitalLocaleRules.requireCurrency(raw))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("currencyCode"));
    }

    @Test
    @DisplayName("an IANA zone is accepted, free text is refused on its field")
    void timeZoneIsValidated() {
        assertThat(HospitalLocaleRules.requireTimeZone(" Africa/Ouagadougou ")).isEqualTo("Africa/Ouagadougou");
        assertThatThrownBy(() -> HospitalLocaleRules.requireTimeZone("Ouagadougou time"))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getField()).isEqualTo("timeZone"));
    }

    @Test
    @DisplayName("the hospital's own zone decides its day, before its organisation's")
    void hospitalZoneComesFirst() {
        Clock utc = Clock.fixed(Instant.parse("2026-11-20T23:30:00Z"), ZoneOffset.UTC);
        Organization organisation = new Organization();
        organisation.setDefaultTimezone("Africa/Ouagadougou");
        Hospital hospital = new Hospital();
        hospital.setOrganization(organisation);

        // No zone of its own: the organisation's (UTC+0), still the 20th.
        assertThat(HospitalCalendar.zoneOf(hospital, utc)).isEqualTo(ZoneId.of("Africa/Ouagadougou"));
        assertThat(HospitalCalendar.today(hospital, utc)).hasToString("2026-11-20");

        // Its own zone (UTC+3): already the 21st there.
        hospital.setTimeZone("Africa/Nairobi");
        assertThat(HospitalCalendar.zoneOf(hospital, utc)).isEqualTo(ZoneId.of("Africa/Nairobi"));
        assertThat(HospitalCalendar.today(hospital, utc)).hasToString("2026-11-21");
    }

    @Test
    @DisplayName("a blank zone on the hospital falls back to the organisation's")
    void blankHospitalZoneFallsBack() {
        Clock utc = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
        Organization organisation = new Organization();
        organisation.setDefaultTimezone("Africa/Lagos");
        Hospital hospital = new Hospital();
        hospital.setOrganization(organisation);
        hospital.setTimeZone("  ");

        assertThat(HospitalCalendar.zoneOf(hospital, utc)).isEqualTo(ZoneId.of("Africa/Lagos"));
    }
}
