package com.example.hms.model.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Convert;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * Wiring contract test for S-05 Phase 2 (slice 1).
 *
 * <p>Confirms that {@link Dispense#notes} is annotated with
 * {@code @Convert(converter = EncryptedStringConverter.class)} so that the JPA
 * provider applies AES-256-GCM at-rest encryption transparently. The converter's
 * cryptographic behaviour is covered by
 * {@code com.example.hms.security.EncryptedStringConverterTest}; this test guards
 * against accidental removal of the annotation during refactors.
 */
class DispenseEncryptionWiringTest {

    @Test
    void notesField_hasEncryptedStringConverter() throws NoSuchFieldException {
        Field notes = Dispense.class.getDeclaredField("notes");

        Convert convert = notes.getAnnotation(Convert.class);

        assertThat(convert)
            .as("Dispense.notes must be annotated with @Convert for at-rest PHI encryption")
            .isNotNull();
        assertThat(convert.converter())
            .as("Dispense.notes converter must be EncryptedStringConverter")
            .isEqualTo(EncryptedStringConverter.class);
    }
}
