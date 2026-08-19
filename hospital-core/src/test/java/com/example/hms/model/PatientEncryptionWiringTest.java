package com.example.hms.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Convert;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Wiring contract test for S-05 Phase 2 (slice 3).
 *
 * <p>Confirms that the PHI free-text fields on {@link Patient} that are NOT used
 * in repository queries carry {@code @Convert(converter = EncryptedStringConverter.class)}
 * so the JPA provider applies AES-256-GCM at-rest encryption transparently.
 *
 * <p>Cryptographic correctness is covered by {@code EncryptedStringConverterTest};
 * this test guards against accidental removal of the annotation during refactors,
 * and documents the explicit decision to NOT encrypt {@code phoneNumberPrimary},
 * {@code phoneNumberSecondary}, and {@code email} (which are queried with
 * equality and {@code LIKE}, indexed, and unique-constrained).
 */
class PatientEncryptionWiringTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "address",
        "addressLine1",
        "addressLine2",
        "emergencyContactName",
        "emergencyContactPhone",
        "emergencyContactRelationship",
        "allergies",
        "medicalHistorySummary",
        "careTeamNotes",
        "chronicConditions"
    })
    void encryptedPhiFields_haveEncryptedStringConverter(String fieldName) throws NoSuchFieldException {
        Field field = Patient.class.getDeclaredField(fieldName);
        Convert convert = field.getAnnotation(Convert.class);

        assertThat(convert)
            .as("Patient.%s must be annotated with @Convert for at-rest PHI encryption", fieldName)
            .isNotNull();
        assertThat(convert.converter())
            .as("Patient.%s converter must be EncryptedStringConverter", fieldName)
            .isEqualTo(EncryptedStringConverter.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"phoneNumberPrimary", "phoneNumberSecondary", "email"})
    void queriedLookupFields_areDeliberatelyNotEncrypted(String fieldName) throws NoSuchFieldException {
        // Encrypting these fields would break PatientRepository.findByPhoneNumberPrimary,
        // findByEmailContainingIgnoreCase, the LIKE-based search, the unique constraints,
        // and the idx_patient_email index. This test documents the deliberate exclusion.
        Field field = Patient.class.getDeclaredField(fieldName);
        Convert convert = field.getAnnotation(Convert.class);

        assertThat(convert)
            .as("Patient.%s must NOT be annotated with @Convert — encryption would break "
                + "lookup queries (LIKE, equality, unique constraint, or index).", fieldName)
            .isNull();
    }
}
