package com.example.hms.payload.dto.recordaccess;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r66 D2904: the release form's short reason answered « motif : la taille doit être comprise entre 10 et
 * 1000 » and an adolescent age of 19 « âge : doit être inférieur ou égal à 18 »: bean validation's own
 * wording behind a field label. Each constraint of the two bodies names a sentence of the application's
 * bundles, which {@code GlobalExceptionHandler.constraintReason} passes through unchanged.
 * ({@code ConstraintMessageKeyGuardTest} checks that the keys exist in every bundle.)
 */
class SensitivePolicyRequestMessagesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void aShortBlankOrOverlongReleaseReason_namesTheReleaseSentence() {
        for (String reason : new String[] {"court", "   ", null, "x".repeat(1001)}) {
            Set<ConstraintViolation<MinorResultReleaseRequestDTO>> violations =
                validator.validate(new MinorResultReleaseRequestDTO(UUID.randomUUID(), reason, true));
            assertThat(violations).as("reason <%s>", reason).isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getMessageTemplate())
                    .isEqualTo("{proxyRelease.reason.length}"));
        }
        assertThat(validator.validate(new MinorResultReleaseRequestDTO(UUID.randomUUID(),
            "Conseil fait, à la demande des parents.", true))).isEmpty();
    }

    @Test
    void anAdolescentAgeOutOfRange_namesTheRangeSentence() {
        for (int age : new int[] {-1, 19}) {
            assertThat(validator.validate(new UpdateAdolescentConfidentialityAgeRequestDTO(age)))
                .as("age %d", age).singleElement()
                .satisfies(violation -> assertThat(violation.getMessageTemplate()).isEqualTo("{adolescentAge.range}"));
        }
        for (Integer age : new Integer[] {null, 0, 1, 12, 18}) {
            assertThat(validator.validate(new UpdateAdolescentConfidentialityAgeRequestDTO(age))).isEmpty();
        }
    }

    @Test
    void theFrenchSentencesSayWhatToFix() throws IOException {
        Properties french = new Properties();
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/i18n/messages-patient_fr.properties"), StandardCharsets.UTF_8)) {
            french.load(reader);
        }
        assertThat(french.getProperty("proxyRelease.reason.length")).contains("motif", "10", "1 000 caractères")
            .doesNotContain("taille");
        assertThat(french.getProperty("adolescentAge.range")).contains("entre 0 et 18 ans")
            .doesNotContain("inférieur ou égal");
    }
}
