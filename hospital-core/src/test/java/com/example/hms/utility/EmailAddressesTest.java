package com.example.hms.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one address rule the sender and the email-change request share. */
class EmailAddressesTest {

    @Test
    void acceptsWhatTheSenderAccepts() {
        assertThat(EmailAddresses.isDeliverable("a.b+tag@example-mail.co")).isTrue();
    }

    @Test
    void refusesWhatTheSenderRefuses() {
        assertThat(EmailAddresses.isDeliverable("someone@example.c")).as("one-letter TLD").isFalse();
        assertThat(EmailAddresses.isDeliverable("no-at-sign.example.com")).isFalse();
        assertThat(EmailAddresses.isDeliverable("two@@example.com")).isFalse();
        assertThat(EmailAddresses.isDeliverable("space in@example.com")).isFalse();
        assertThat(EmailAddresses.isDeliverable(" ")).isFalse();
        assertThat(EmailAddresses.isDeliverable(null)).isFalse();
    }

    @Test
    void normalisesAsRegistrationDoes() {
        assertThat(EmailAddresses.normalize("  A.B@Example.COM ")).isEqualTo("a.b@example.com");
        assertThat(EmailAddresses.normalize("   ")).isNull();
        assertThat(EmailAddresses.normalize(null)).isNull();
    }

    @Test
    void hashesTheNormalisedAddressWithoutKeepingIt() {
        String h = EmailAddresses.hash("  Victim@Example.COM ");
        assertThat(h).hasSize(64).matches("[0-9a-f]+").doesNotContain("victim");
        assertThat(EmailAddresses.hash("victim@example.com")).isEqualTo(h);
        assertThat(EmailAddresses.hash("other@example.com")).isNotEqualTo(h);
    }
}
