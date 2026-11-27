package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Account links carry one token, in the fragment (K4)")
class ActivationLinksTest {

    @Test
    void theActivationLinkCarriesTheTokenInItsFragmentAndNoAddress() {
        String link = ActivationLinks.activation("https://dev.e-keneya.com", "t-1_AbC");

        assertThat(link).isEqualTo("https://dev.e-keneya.com/verify#token=t-1_AbC");
        URI uri = URI.create(link);
        assertThat(uri.getQuery()).isNull();
        assertThat(uri.getPath()).isEqualTo("/verify");
        assertThat(uri.getFragment()).isEqualTo("token=t-1_AbC");
        assertThat(link).doesNotContain("@", "email");
    }

    @Test
    void theResetLinkCarriesTheTokenInItsFragment() {
        String link = ActivationLinks.passwordReset("https://x", "abc-123");

        assertThat(link).isEqualTo("https://x/reset-password#token=abc-123");
        assertThat(URI.create(link).getQuery()).isNull();
    }

    @Test
    void aTokenIsEncodedSoTheFragmentHoldsOnlyIt() {
        assertThat(ActivationLinks.activation("https://x", "a&b=c#d")).isEqualTo("https://x/verify#token=a%26b%3Dc%23d");
    }
}
