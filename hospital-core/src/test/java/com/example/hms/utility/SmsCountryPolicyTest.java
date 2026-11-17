package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmsCountryPolicy")
class SmsCountryPolicyTest {

    @Test
    @DisplayName("Burkina Faso numbers are E.164 with exactly eight national digits")
    void burkinaFaso() {
        assertThat(SmsCountryPolicy.allows("+22670707070")).isTrue();
        assertThat(SmsCountryPolicy.allows("+2267070707")).isFalse();
        assertThat(SmsCountryPolicy.allows("+226707070701")).isFalse();
        assertThat(SmsCountryPolicy.allows("22670707070")).as("no plus").isFalse();
        assertThat(SmsCountryPolicy.allows("+226 70 70 70 70")).as("not normalised").isFalse();
    }

    @Test
    @DisplayName("the other WAEMU countries are allowed, anything else is refused")
    void waemuOnly() {
        assertThat(SmsCountryPolicy.allows("+22501020304")).isTrue();
        assertThat(SmsCountryPolicy.allows("+22376543210")).isTrue();
        assertThat(SmsCountryPolicy.allows("+33612345678")).isFalse();
        assertThat(SmsCountryPolicy.allows("+12025550123")).isFalse();
        assertThat(SmsCountryPolicy.allows(null)).isFalse();
        assertThat(SmsCountryPolicy.allows("")).isFalse();
    }
}
