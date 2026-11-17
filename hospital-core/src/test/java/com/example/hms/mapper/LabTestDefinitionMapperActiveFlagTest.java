package com.example.hms.mapper;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabTestDefinitionRequestDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D535: a DRAFT definition was returned with isActive=true. */
class LabTestDefinitionMapperActiveFlagTest {

    private final LabTestDefinitionMapper mapper = new LabTestDefinitionMapper();

    @Test
    void create_asksForActive_theNewDraftIsNotActive() {
        LabTestDefinitionRequestDTO dto = LabTestDefinitionRequestDTO.builder()
            .testCode("GLU").name("Glucose").active(true).build();

        LabTestDefinition entity = mapper.toEntity(dto, null);

        assertThat(entity.getApprovalStatus()).isEqualTo(LabTestDefinitionApprovalStatus.DRAFT);
        assertThat(entity.isActive()).isFalse();
        assertThat(mapper.toDto(entity).isActive()).isFalse();
    }

    @Test
    void legacyDraftRowWithActiveColumnTrue_isReportedInactive() {
        LabTestDefinition entity = LabTestDefinition.builder().testCode("GLU").name("Glucose")
            .active(true).approvalStatus(LabTestDefinitionApprovalStatus.DRAFT).build();

        assertThat(mapper.toDto(entity).isActive()).isFalse();
    }

    @Test
    void activatedDefinition_isReportedActive() {
        LabTestDefinition entity = LabTestDefinition.builder().testCode("GLU").name("Glucose")
            .active(true).approvalStatus(LabTestDefinitionApprovalStatus.ACTIVE).build();

        assertThat(mapper.toDto(entity).isActive()).isTrue();
    }
}
