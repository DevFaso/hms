package com.example.hms.service.clinicalrules;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClinicalOverrideReasonTest {
    @Test
    void countsNonWhitespaceCharactersRatherThanPadding() {
        assertThat(ClinicalOverrideReason.isSufficient(null)).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("ok")).isFalse();
        assertThat(ClinicalOverrideReason.trim("\u00a0  12345 67890\ufeff")).isEqualTo("12345 67890");
        assertThat(ClinicalOverrideReason.isSufficient("          ")).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("123456789   ")).isFalse();
        assertThat(ClinicalOverrideReason.isSufficient("12345\u00a0 67890")).isTrue();
        assertThat(ClinicalOverrideReason.isSufficient("a\u00a0 b c d e f g h i")).isFalse();
    }
    @Test
    void breakGlassRequestRejectsPaddingAndAcceptsTenNonWhitespaceCharacters() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String reason : java.util.List.of("ok", "          ", "123456789   ")) {
                var dto = com.example.hms.payload.dto.BreakGlassDeclareRequestDTO.builder()
                    .patientId(java.util.UUID.randomUUID()).hospitalId(java.util.UUID.randomUUID()).reason(reason).build();
                assertThat(validator.validate(dto)).anyMatch(v -> v.getPropertyPath().toString().equals("reason"));
            }
            var dto = com.example.hms.payload.dto.BreakGlassDeclareRequestDTO.builder()
                .patientId(java.util.UUID.randomUUID()).hospitalId(java.util.UUID.randomUUID()).reason("12345 67890").build();
            assertThat(validator.validate(dto)).isEmpty();
        }
    }

}
