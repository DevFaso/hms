package com.example.hms.security;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class PatientNationalUniqueIdPrivacyTest {
    private static final String VALUE = "000123456789";
    private static final String HASH = "a".repeat(64);

    @Test
    void entitiesAndWriteDtosCannotSerializeOrPrintTheIdentifier() {
        var mapper = JsonMapper.builder().build();
        Patient patient = Patient.builder().nationalUniqueId(VALUE).nationalUniqueIdHash(HASH).build();
        var create = PatientRequestDTO.builder().nationalUniqueId(VALUE).build();
        var patch = new PatientProfileUpdateRequestDTO(); patch.setNationalUniqueId(VALUE);
        for (Object value : new Object[] { patient, create, patch }) {
            assertThat(mapper.writeValueAsString(value)).doesNotContain(VALUE, HASH, "nationalUniqueId");
            assertThat(value.toString()).doesNotContain(VALUE, HASH);
        }
    }

    @Test
    void ordinaryPatientMappingAlwaysReturnsOnlyTheLastFourDigits() {
        Patient patient = Patient.builder().firstName("Test").lastName("Identity")
            .nationalUniqueId(VALUE).nationalUniqueIdHash(HASH).build();
        var dto = new PatientMapper(TestMessageSources.bundles()).toPatientDTO(patient);
        assertThat(dto.getNationalUniqueIdMasked()).isEqualTo("•••• •••• 6789");
        String json = JsonMapper.builder().build().writeValueAsString(dto);
        assertThat(json).doesNotContain(VALUE, HASH, "nationalUniqueIdHash");
        assertThat(json).contains("nationalUniqueIdMasked");
    }
}
