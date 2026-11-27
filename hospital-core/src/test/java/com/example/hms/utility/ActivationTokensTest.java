package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Activation tokens: opaque, stored hashed, older links honoured (K4)")
class ActivationTokensTest {

    private static final String OLD_LINK_TOKEN = "3f2b8c1e-6a4d-4e0b-9c57-1d2e3f4a5b6c";

    @Test
    void aNewTokenIsRandomUrlSafeAndStoredAsItsHash() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ActivationTokens.Issued issued = ActivationTokens.issue();
            assertThat(issued.raw()).matches("[A-Za-z0-9_-]{43}");
            assertThat(issued.stored()).matches("[0-9a-f]{64}").isNotEqualTo(issued.raw());
            assertThat(ActivationTokens.matches(issued.stored(), issued.raw())).isTrue();
            assertThat(ActivationTokens.storedForms(issued.raw())).containsExactly(issued.stored());
            assertThat(seen.add(issued.raw())).isTrue();
            assertThat(issued.toString()).doesNotContain(issued.raw(), issued.stored());
        }
    }

    @Test
    void whatIsStoredIsNeverAcceptedAsTheToken() {
        ActivationTokens.Issued issued = ActivationTokens.issue();

        assertThat(ActivationTokens.matches(issued.stored(), issued.stored())).isFalse();
        assertThat(ActivationTokens.matches(issued.stored(), "something-else")).isFalse();
        assertThat(ActivationTokens.matches(issued.stored(), null)).isFalse();
        assertThat(ActivationTokens.matches(issued.stored(), " ")).isFalse();
        assertThat(ActivationTokens.matches(null, issued.raw())).isFalse();
        // A lookup by the stored value looks for the hash of that value, which no account holds.
        assertThat(ActivationTokens.storedForms(issued.stored())).doesNotContain(issued.stored());
    }

    @Test
    void aLinkMailedBeforeIsMatchedAsItWasSent() {
        assertThat(ActivationTokens.matches(OLD_LINK_TOKEN, OLD_LINK_TOKEN)).isTrue();
        assertThat(ActivationTokens.matches(OLD_LINK_TOKEN, " " + OLD_LINK_TOKEN + " ")).isTrue();
        assertThat(ActivationTokens.matches(OLD_LINK_TOKEN, "3f2b8c1e-6a4d-4e0b-9c57-000000000000")).isFalse();
        assertThat(ActivationTokens.storedForms(OLD_LINK_TOKEN)).hasSize(2).contains(OLD_LINK_TOKEN);
    }

    @Test
    void nothingIsLookedUpForABlankToken() {
        assertThat(ActivationTokens.storedForms(null)).isEmpty();
        assertThat(ActivationTokens.storedForms("  ")).isEmpty();
    }
}
