package com.example.hms.mapper;

import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LabCultureDefinitionMappingTest {
    private final LabTestDefinitionMapper mapper = new LabTestDefinitionMapper();

    @Test
    void cultureIsExplicitAndAnOlderClientCannotSilentlyClearIt() {
        var definition = mapper.toEntity(LabTestDefinitionRequestDTO.builder().culture(true).build(), null);
        assertThat(definition.isCulture()).isTrue();
        assertThat(mapper.toDto(definition).isCulture()).isTrue();
        mapper.updateEntityFromDto(LabTestDefinitionRequestDTO.builder().build(), definition);
        assertThat(definition.isCulture()).isTrue();
        mapper.updateEntityFromDto(LabTestDefinitionRequestDTO.builder().culture(false).build(), definition);
        assertThat(definition.isCulture()).isFalse();
        assertThat(mapper.toEntity(LabTestDefinitionRequestDTO.builder().build(), null).isCulture()).isFalse();
    }
}
