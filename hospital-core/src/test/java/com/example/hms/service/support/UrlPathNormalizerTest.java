package com.example.hms.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link UrlPathNormalizer} so PR #315's new normalization helpers
 * meet the SonarCloud New Code coverage gate and so a regression in the
 * leading/trailing-slash policy can't silently corrupt confirmation-email
 * URLs or patient-document storage keys.
 */
@DisplayName("UrlPathNormalizer (PR #315)")
class UrlPathNormalizerTest {

    // ── fragment() ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "fragment(\"{0}\") → \"/appointments/reschedule/\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("fragment — null / blank / whitespace input falls back")
    void fragment_blankInputs_returnFallback(String raw) {
        assertThat(UrlPathNormalizer.fragment(raw, "/appointments/reschedule/"))
            .isEqualTo("/appointments/reschedule/");
    }

    @ParameterizedTest(name = "fragment(\"{0}\") → \"{1}\"")
    @CsvSource({
        // already correct
        "/appointments/reschedule/,             /appointments/reschedule/",
        // no leading slash
        "appointments/reschedule/,              /appointments/reschedule/",
        // no trailing slash
        "/appointments/reschedule,              /appointments/reschedule/",
        // neither leading nor trailing slash
        "appointments/reschedule,               /appointments/reschedule/",
        // doubled leading
        "//appointments/reschedule/,            /appointments/reschedule/",
        // triple+ leading
        "////appointments/reschedule/,          /appointments/reschedule/",
        // surrounding whitespace
        "'  /appointments/reschedule/  ',       /appointments/reschedule/",
        // single segment
        "x,                                     /x/",
        // already-trailing slash should remain singular (no double slash)
        "/x/,                                   /x/",
        // doubled trailing — must collapse to single trailing
        "/x//,                                  /x/",
        // doubled leading AND doubled trailing
        "//x//,                                 /x/"
    })
    @DisplayName("fragment — normalises configured paths to /.../ shape")
    void fragment_normalisesShape(String raw, String expected) {
        assertThat(UrlPathNormalizer.fragment(raw, "/FALLBACK/"))
            .isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("fragment — is idempotent")
    void fragment_idempotent() {
        String once = UrlPathNormalizer.fragment("appointments/reschedule", "/F/");
        String twice = UrlPathNormalizer.fragment(once, "/F/");
        assertThat(twice).isEqualTo(once);
    }

    // ── prefix() ──────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "prefix(\"{0}\") → \"/uploads\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("prefix — null / blank / whitespace input falls back")
    void prefix_blankInputs_returnFallback(String raw) {
        assertThat(UrlPathNormalizer.prefix(raw, "/uploads"))
            .isEqualTo("/uploads");
    }

    @ParameterizedTest(name = "prefix(\"{0}\") → \"{1}\"")
    @CsvSource({
        // already correct (no trailing slash)
        "/uploads,                              /uploads",
        // no leading slash
        "uploads,                               /uploads",
        // trailing slash present → stripped
        "/uploads/,                             /uploads",
        // multiple trailing slashes → all stripped
        "/uploads////,                          /uploads",
        // neither leading nor trailing slash
        "uploads,                               /uploads",
        // doubled leading
        "//uploads,                             /uploads",
        // doubled leading and trailing
        "//uploads//,                           /uploads",
        // surrounding whitespace
        "'  /uploads  ',                        /uploads",
        // single segment
        "static,                                /static",
        // root-only edge case must not collapse to empty string
        "/,                                     /"
    })
    @DisplayName("prefix — normalises configured prefixes to /... shape (no trailing slash)")
    void prefix_normalisesShape(String raw, String expected) {
        assertThat(UrlPathNormalizer.prefix(raw, "/FALLBACK"))
            .isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("prefix — is idempotent")
    void prefix_idempotent() {
        String once = UrlPathNormalizer.prefix("uploads/", "/F");
        String twice = UrlPathNormalizer.prefix(once, "/F");
        assertThat(twice).isEqualTo(once);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("prefix — strips ALL trailing slashes (regression: '/static/files//' → '/static/files')")
    void prefix_stripsAllTrailingSlashes() {
        assertThat(UrlPathNormalizer.prefix("/static/files//", "/F"))
            .isEqualTo("/static/files");
    }
}
