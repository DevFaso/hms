package com.example.hms.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Convert;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Wiring contract test for S-05 Phase 2 (slice 2).
 *
 * <p>Confirms that the three free-text PHI fields on {@link Prescription} —
 * {@code instructions}, {@code overrideReason}, and {@code notes} — carry
 * {@code @Convert(converter = EncryptedStringConverter.class)} so that the JPA
 * provider applies AES-256-GCM at-rest encryption transparently. Cryptographic
 * correctness is covered by {@code EncryptedStringConverterTest}; this test
 * guards against accidental removal of the annotation during refactors.
 */
class PrescriptionEncryptionWiringTest {

    @Test
    void instructionsField_hasEncryptedStringConverter() throws NoSuchFieldException {
        assertEncrypted("instructions");
    }

    @Test
    void overrideReasonField_hasEncryptedStringConverter() throws NoSuchFieldException {
        assertEncrypted("overrideReason");
    }

    @Test
    void notesField_hasEncryptedStringConverter() throws NoSuchFieldException {
        assertEncrypted("notes");
    }

    private static void assertEncrypted(String fieldName) throws NoSuchFieldException {
        Field field = Prescription.class.getDeclaredField(fieldName);
        Convert convert = field.getAnnotation(Convert.class);

        assertThat(convert)
            .as("Prescription.%s must be annotated with @Convert for at-rest PHI encryption", fieldName)
            .isNotNull();
        assertThat(convert.converter())
            .as("Prescription.%s converter must be EncryptedStringConverter", fieldName)
            .isEqualTo(EncryptedStringConverter.class);
    }
}
