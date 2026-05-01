package com.example.hms.mapper.integration;

import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2DataElementMapping;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class Dhis2DataElementMappingMapper {

    public Dhis2DataElementMappingResponseDTO toResponseDTO(Dhis2DataElementMapping entity) {
        if (entity == null) {
            return null;
        }
        return new Dhis2DataElementMappingResponseDTO(
            entity.getId(),
            entity.getHospital() != null ? entity.getHospital().getId() : null,
            entity.getHmsConceptSystem(),
            entity.getHmsConceptCode(),
            entity.getDhis2DataElementUid(),
            entity.getDhis2CategoryOptionComboUid(),
            entity.getPeriodType(),
            entity.getDatasetUid(),
            entity.isActive(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public Dhis2DataElementMapping toEntity(Dhis2DataElementMappingRequestDTO dto, Hospital hospital) {
        if (dto == null) {
            return null;
        }
        return Dhis2DataElementMapping.builder()
            .hospital(hospital)
            .hmsConceptSystem(trim(dto.hmsConceptSystem()))
            .hmsConceptCode(trim(dto.hmsConceptCode()))
            .dhis2DataElementUid(trim(dto.dhis2DataElementUid()))
            .dhis2CategoryOptionComboUid(trim(dto.dhis2CategoryOptionComboUid()))
            .periodType(dto.periodType())
            .datasetUid(trim(dto.datasetUid()))
            .active(dto.active() == null || dto.active())
            .build();
    }

    public void applyToEntity(Dhis2DataElementMappingRequestDTO dto, Hospital hospital,
                              Dhis2DataElementMapping target) {
        if (dto == null || target == null) {
            return;
        }
        target.setHospital(hospital);
        target.setHmsConceptSystem(trim(dto.hmsConceptSystem()));
        target.setHmsConceptCode(trim(dto.hmsConceptCode()));
        target.setDhis2DataElementUid(trim(dto.dhis2DataElementUid()));
        target.setDhis2CategoryOptionComboUid(trim(dto.dhis2CategoryOptionComboUid()));
        target.setPeriodType(dto.periodType());
        target.setDatasetUid(trim(dto.datasetUid()));
        if (dto.active() != null) {
            target.setActive(dto.active());
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
