package com.example.hms.mapper;

import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TreatmentMapper {

    private final ServiceTranslationMapper translationMapper;

    public TreatmentResponseDTO toTreatmentResponseDTO(Treatment treatment, String language) {
        if (treatment == null) return null;

                Map<String, ServiceTranslationResponseDTO> translations = Optional.ofNullable(treatment.getTranslations())
                                .orElse(Collections.emptySet())
                                .stream()
                                .map(st -> {
                                        ServiceTranslationResponseDTO dto = translationMapper.toDto(st);
                                        if (dto != null) {
                                                dto.setTreatmentName(treatment.getName());
                                        }
                                        return dto;
                                })
                                .filter(t -> language == null || t.getLanguageCode().equalsIgnoreCase(language))
                                .collect(Collectors.toMap(
                                                ServiceTranslationResponseDTO::getLanguageCode,
                                                t -> t,
                                                (existing, replacement) -> existing
                                ));

        Department dept = treatment.getDepartment();
        Hospital hosp = treatment.getHospital();

        return TreatmentResponseDTO.builder()
                .id(treatment.getId())
                .name(treatment.getName())
                .description(treatment.getDescription())
                .departmentId(dept != null ? dept.getId() : null)
                .hospitalId(hosp != null ? hosp.getId() : null)
                .departmentName(Optional.ofNullable(dept).map(Department::getName).orElse(null))
                .hospitalName(Optional.ofNullable(hosp).map(Hospital::getName).orElse(null))
                .creatorId(Optional.ofNullable(treatment.getAssignment())
                        .map(UserRoleHospitalAssignment::getUser)
                        .map(User::getId)
                        .orElse(null))
                .creatorName(Optional.ofNullable(treatment.getAssignment())
                        .map(UserRoleHospitalAssignment::getUser)
                        .map(user -> {
                            String first = Optional.ofNullable(user.getFirstName()).orElse("");
                            String last = Optional.ofNullable(user.getLastName()).orElse("");
                            return (first + " " + last).trim();
                        })
                        .orElse("Unknown"))
                .price(treatment.getPrice())
                .durationMinutes(treatment.getDurationMinutes())
                .active(treatment.getActive())
                .createdAt(treatment.getCreatedAt())
                .updatedAt(treatment.getUpdatedAt())
                .translations(translations != null && !translations.isEmpty() ? translations : null)
                .build();
    }

    public Treatment toTreatment(TreatmentRequestDTO dto, Department department,
                                 Hospital hospital, UserRoleHospitalAssignment assignment) {
        return Treatment.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .department(department)
                .hospital(hospital)
                .assignment(assignment)
                .price(dto.getPrice())
                .durationMinutes(dto.getDurationMinutes())
                .active(dto.isActive())
                .translations(new HashSet<>())
                .build();
    }

    public void updateTreatmentFromDto(TreatmentRequestDTO dto, Treatment treatment,
                                       Department department, Hospital hospital) {
                treatment.setName(dto.getName());
                treatment.setDescription(dto.getDescription());
                treatment.setDepartment(department);
                treatment.setHospital(hospital);
                treatment.setPrice(dto.getPrice());
                treatment.setDurationMinutes(dto.getDurationMinutes());
                treatment.setActive(dto.isActive());
                if (treatment.getTranslations() == null) {
                        treatment.setTranslations(new HashSet<>());
                }
    }
}

