package com.example.hms.security;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientNationalUniqueIdTest {
    private static final String VALUE = "000123456789";
    private static final String VECTOR = "46feb9fd8c7322d33e7b2fc3c9cd330253e0707fd1bf9286bd4164094f85ed7a";
    private SecretKey previousKey;
    private byte[] syntheticKey;

    @BeforeEach
    void installSyntheticKey() {
        previousKey = EncryptionKeyHolder.getKeyForTesting();
        syntheticKey = new byte[32];
        for (int i = 0; i < syntheticKey.length; i++) syntheticKey[i] = (byte) i;
        EncryptionKeyHolder.setKeyForTesting(new SecretKeySpec(syntheticKey, "AES"));
    }

    @AfterEach
    void restoreKey() {
        EncryptionKeyHolder.setKeyForTesting(previousKey);
    }

    @ParameterizedTest
    @ValueSource(strings = {VALUE, "0001 2345 6789", "0001-2345-6789", "0001\u00a02345\u202f6789", "0001\u20112345\u20116789"})
    void canonicalStringRetainsLeadingZerosAndAcceptsSpacesAndDashes(String input) {
        assertThat(PatientNationalUniqueId.normalize(input)).isEqualTo(VALUE);
        assertThat(PatientNationalUniqueId.fingerprint(input)).isEqualTo(VECTOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678901", "1234567890123", "00012345678A", "１２３４５６７８９０１２", "1234.5678.9012"})
    void invalidValuesAreRefusedOnTheFieldWithoutEchoingTheInput(String input) {
        assertThatThrownBy(() -> PatientNationalUniqueId.normalize(input))
            .isInstanceOfSatisfying(FieldBusinessException.class, error -> {
                assertThat(error.getField()).isEqualTo("nationalUniqueId");
                assertThat(error.getMessage()).doesNotContain(input);
            });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  -\t\u202f"})
    void absentValuesNeedNeitherANumberNorAnEncryptionKey(String input) {
        EncryptionKeyHolder.setKeyForTesting(null);
        assertThat(PatientNationalUniqueId.normalize(input)).isNull();
        assertThat(PatientNationalUniqueId.fingerprint(input)).isNull();
        assertThat(PatientNationalUniqueId.masked(input)).isNull();
    }

    @Test
    void fingerprintUsesTheServerKeyRatherThanAPublicDigest() {
        assertThat(PatientNationalUniqueId.fingerprint(VALUE)).hasSize(64).isEqualTo(VECTOR);
        syntheticKey[0] = 99;
        EncryptionKeyHolder.setKeyForTesting(new SecretKeySpec(syntheticKey, "AES"));
        assertThat(PatientNationalUniqueId.fingerprint(VALUE)).isNotEqualTo(VECTOR);
        EncryptionKeyHolder.setKeyForTesting(null);
        assertThatThrownBy(() -> PatientNationalUniqueId.fingerprint(VALUE))
            .isInstanceOf(FieldBusinessException.class).hasMessageNotContaining(VALUE);
    }

    @Test
    void presentationNeverTurnsTheValueIntoANumberOrInventsAChecksum() {
        assertThat(PatientNationalUniqueId.formatted(VALUE)).isEqualTo("0001 2345 6789");
        assertThat(PatientNationalUniqueId.masked(VALUE)).isEqualTo("•••• •••• 6789");
        assertThat(PatientNationalUniqueId.normalize("000000000000")).isEqualTo("000000000000");
    }

    @Test
    void overrideAuditKeepsTheReasonButRemovesPlainFormattedAndHashedIdentifiers() {
        String reason = "Verified 0001 2345 6789 with patient. Ref " + VECTOR + ". Phone 70123456.";
        assertThat(PatientNationalUniqueId.auditReason(reason))
            .isEqualTo("Verified [redacted] with patient. Ref [redacted]. Phone 70123456.");
        assertThat(PatientNationalUniqueId.auditReason("Checked " + VALUE + " - with patient"))
            .isEqualTo("Checked [redacted] - with patient");
    }

    @Test
    void existingConverterEncryptsTheCanonicalValueAndPreservesItOnRead() {
        var converter = new EncryptedStringConverter();
        String stored = converter.convertToDatabaseColumn(VALUE);
        assertThat(stored).startsWith("gcm1:").doesNotContain(VALUE);
        assertThat(converter.convertToDatabaseColumn(VALUE)).isNotEqualTo(stored);
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(VALUE);
    }
}
