package com.example.hms.mapper;

import com.example.hms.model.ServiceTranslation;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.ServiceTranslationRequestDTO;
import com.example.hms.payload.dto.ServiceTranslationResponseDTO;
import org.springframework.stereotype.Component;


@Component
public class ServiceTranslationMapper {

    public ServiceTranslation toEntity(
            ServiceTranslationRequestDTO dto,
            Treatment treatment,
            UserRoleHospitalAssignment assignment
    ) {
        return ServiceTranslation.builder()
                .languageCode(dto.getLanguageCode())
                .name(dto.getName())
                .description(dto.getDescription())
                .treatment(treatment)
                .assignment(assignment)
                .build();
    }

    public ServiceTranslationResponseDTO toDto(ServiceTranslation translation) {
        return ServiceTranslationResponseDTO.builder()
                .id(translation.getId())
                .treatmentId(translation.getTreatment().getId())
                .languageCode(translation.getLanguageCode())
                .name(translation.getName())
                .description(translation.getDescription())
                .build();
    }

    public void updateEntity(ServiceTranslation translation, ServiceTranslationRequestDTO dto) {
        translation.setLanguageCode(dto.getLanguageCode());
        translation.setName(dto.getName());
        translation.setDescription(dto.getDescription());
    }
}
