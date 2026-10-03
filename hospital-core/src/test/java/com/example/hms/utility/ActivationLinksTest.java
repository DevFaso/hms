package com.example.hms.utility;

import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationLinksTest {

    @Test
    void aPlusInTheAddressSurvivesTheRoundTrip() {
        String link = ActivationLinks.build("https://dev.e-keneya.com", "a+b@example.com", "t-1");

        assertThat(link).isEqualTo("https://dev.e-keneya.com/verify?email=a%2Bb%40example.com&token=t-1");
        // What the server reads back from the query: the address as typed.
        var params = UriComponentsBuilder.fromUriString(link).build(true).getQueryParams();
        assertThat(java.net.URLDecoder.decode(params.getFirst("email"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("a+b@example.com");
    }

    @Test
    void aPlainAddressAndTokenAreUnchangedButEncoded() {
        assertThat(ActivationLinks.build("https://x", "awa@example.com", "abc-123"))
                .isEqualTo("https://x/verify?email=awa%40example.com&token=abc-123");
    }
}
