package com.example.hms.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link EncryptionKeyHolder} — security task S-05. */
class EncryptionKeyHolderTest {

    @AfterEach
    void resetState() {
        EncryptionKeyHolder.setKeyForTesting(null);
    }

    @Test
    void emptyConfiguredKey_leavesHolderUnconfigured() {
        EncryptionKeyHolder.setKeyForTesting(null);
        var holder = new EncryptionKeyHolder("");
        holder.init();
        assertThat(EncryptionKeyHolder.isConfigured()).isFalse();
        assertThat(EncryptionKeyHolder.getKey()).isNull();
    }

    @Test
    void blankConfiguredKey_isTreatedAsUnconfigured() {
        EncryptionKeyHolder.setKeyForTesting(null);
        var holder = new EncryptionKeyHolder("   ");
        holder.init();
        assertThat(EncryptionKeyHolder.isConfigured()).isFalse();
    }

    @Test
    void validBase64Key_loadsSuccessfully() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        var holder = new EncryptionKeyHolder(Base64.getEncoder().encodeToString(raw));
        holder.init();
        assertThat(EncryptionKeyHolder.isConfigured()).isTrue();
        assertThat(EncryptionKeyHolder.getKey()).isNotNull();
        assertThat(EncryptionKeyHolder.getKey().getAlgorithm()).isEqualTo("AES");
        assertThat(EncryptionKeyHolder.getKey().getEncoded()).isEqualTo(raw);
    }

    @Test
    void wrongLengthKey_throwsExplicitErrorAtStartup() {
        // 16 bytes = AES-128, our policy requires 32 (AES-256)
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        var holder = new EncryptionKeyHolder(shortKey);
        assertThatThrownBy(holder::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void invalidBase64Key_throwsExplicitErrorAtStartup() {
        var holder = new EncryptionKeyHolder("!!! not base64 !!!");
        assertThatThrownBy(holder::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
