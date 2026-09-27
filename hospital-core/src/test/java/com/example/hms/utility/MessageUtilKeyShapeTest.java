package com.example.hms.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessageUtil#isMessageKey} decides whether an exception's text is
 * looked up in the bundles or shown as written; a sentence must never be
 * looked up and a dotted key must never be shown raw.
 */
class MessageUtilKeyShapeTest {

    /** Other tests read the static source an earlier test left: put it back. */
    private final org.springframework.context.MessageSource previous =
        (org.springframework.context.MessageSource)
            org.springframework.test.util.ReflectionTestUtils.getField(MessageUtil.class, "messageSource");

    @AfterEach
    void restore() {
        MessageUtil.setMessageSource(previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "patient.notFound", "empi.alias-x_1.orphaned", "billing.invoice.hasPayments"})
    void dottedTokensAreKeys(String text) {
        assertThat(MessageUtil.isMessageKey(text)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "a.", ".a", "a..b", "1a.b", "a.b c", "Patient not found.",
        "Unsupported payment method.", "é.b", "a.b\n"})
    void anythingElseIsNot(String text) {
        assertThat(MessageUtil.isMessageKey(text)).isFalse();
    }

    @Test
    void aLongSentenceIsRejectedQuickly() {
        String longText = "a".repeat(200_000) + " x";
        assertThat(MessageUtil.isMessageKey(longText)).isFalse();
    }

    @Test
    void resolveOrRawLooksUpKeysAndLeavesTheRestAlone() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("demo.key", Locale.getDefault(), "Resolved {0}");
        source.setUseCodeAsDefaultMessage(false);
        MessageUtil.setMessageSource(source);

        assertThat(MessageUtil.resolveOrRaw("demo.key", "x")).isEqualTo("Resolved x");
        assertThat(MessageUtil.resolveOrRaw("unknown.key")).isEqualTo("unknown.key");
        assertThat(MessageUtil.resolveOrRaw("Free text stays.")).isEqualTo("Free text stays.");
    }
}
