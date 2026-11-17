package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatientMrnGeneratorTest {

    private final PatientHospitalRegistrationRepository repo = mock(PatientHospitalRegistrationRepository.class);
    private final PatientMrnGenerator generator = new PatientMrnGenerator(repo);

    private static Hospital hospital(String code, String name) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setCode(code);
        h.setName(name);
        return h;
    }

    @Test
    void aMissingHospitalOrHospitalIdIsRefused() {
        assertThatThrownBy(() -> generator.generate(null)).isInstanceOf(IllegalArgumentException.class);
        Hospital noId = new Hospital();
        assertThatThrownBy(() -> generator.generate(noId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesPrefixPlusFourDigits() {
        when(repo.existsByMrnAndHospitalId(anyString(), any())).thenReturn(false);
        assertThat(generator.generate(hospital("ckb-01", "x"))).matches("CKB[0-9]{4}");
    }

    @Test
    void retriesOnCollisionThenGivesUp() {
        when(repo.existsByMrnAndHospitalId(anyString(), any())).thenReturn(true);
        Hospital h = hospital("ABC", "x");
        assertThatThrownBy(() -> generator.generate(h)).isInstanceOf(BusinessException.class);
    }

    @Test
    void prefixFallsBackFromCodeToNameToDefault() {
        assertThat(PatientMrnGenerator.prefixFor(hospital(null, "Saint Luc"))).isEqualTo("SAI");
        assertThat(PatientMrnGenerator.prefixFor(hospital("--", "!!"))).isEqualTo(PatientMrnGenerator.DEFAULT_PREFIX);
        assertThat(PatientMrnGenerator.prefixFor(hospital(null, null))).isEqualTo(PatientMrnGenerator.DEFAULT_PREFIX);
        assertThat(PatientMrnGenerator.prefixFor(hospital("a1", null))).isEqualTo("A1M");
    }
}
