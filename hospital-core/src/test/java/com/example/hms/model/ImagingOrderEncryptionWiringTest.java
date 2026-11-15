package com.example.hms.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;

/**
 * Provisional rule 2 (2026-11-15, V297): the free-text note of a duplicate
 * override can name the patient's history, so it is encrypted at rest.
 */
class ImagingOrderEncryptionWiringTest {

    @Test
    void theDuplicateOverrideNoteIsEncrypted() throws NoSuchFieldException {
        Convert convert = ImagingOrder.class.getDeclaredField("duplicateOverrideNote").getAnnotation(Convert.class);
        assertThat(convert).as("ImagingOrder.duplicateOverrideNote must be encrypted at rest").isNotNull();
        assertThat(convert.converter()).isEqualTo(EncryptedStringConverter.class);
    }
}
