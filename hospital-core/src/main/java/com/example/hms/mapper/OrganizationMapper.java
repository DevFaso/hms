package com.example.hms.mapper;

import com.example.hms.model.Organization;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.*;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class OrganizationMapper {

    public OrganizationResponseDTO toResponseDTO(Organization organization) {
        if (organization == null) return null;

        return OrganizationResponseDTO.builder()
            .id(organization.getId())
            .name(organization.getName())
            .code(organization.getCode())
            .description(organization.getDescription())
            .type(organization.getType())
            .active(organization.isActive())
            .createdAt(organization.getCreatedAt())
            .updatedAt(organization.getUpdatedAt())
            .primaryContactEmail(organization.getPrimaryContactEmail())
            .primaryContactPhone(organization.getPrimaryContactPhone())
            .defaultTimezone(organization.getDefaultTimezone())
            .onboardingNotes(organization.getOnboardingNotes())
            .hospitals(mapHospitalsToMinimal(organization.getHospitals()))
            .build();
    }

    public OrganizationResponseDTO toResponseDTOWithPolicies(Organization organization, 
            List<OrganizationSecurityPolicyResponseDTO> policies) {
        OrganizationResponseDTO dto = toResponseDTO(organization);
        if (dto != null) {
            dto.setSecurityPolicies(policies != null ? policies : Collections.emptyList());
        }
        return dto;
    }

    public Organization toEntity(OrganizationRequestDTO requestDTO) {
        if (requestDTO == null) return null;

        return Organization.builder()
            .name(requestDTO.getName())
            .code(requestDTO.getCode())
            .description(requestDTO.getDescription())
            .type(requestDTO.getType())
            .active(requestDTO.isActive())
            .build();
    }

    public void updateEntity(Organization organization, OrganizationRequestDTO requestDTO) {
        if (organization == null || requestDTO == null) return;

        organization.setName(requestDTO.getName());
        organization.setCode(requestDTO.getCode());
        organization.setDescription(requestDTO.getDescription());
        organization.setType(requestDTO.getType());
        organization.setActive(requestDTO.isActive());
    }

    private List<OrganizationResponseDTO.HospitalMinimalDTO> mapHospitalsToMinimal(java.util.Set<Hospital> hospitals) {
        if (hospitals == null) {
            return Collections.emptyList();
        }

        if (!Hibernate.isInitialized(hospitals)) {
            return Collections.emptyList();
        }

        if (hospitals.isEmpty()) {
            return Collections.emptyList();
        }

        return hospitals.stream()
            .map(hospital -> OrganizationResponseDTO.HospitalMinimalDTO.builder()
                .id(hospital.getId())
                .name(hospital.getName())
                .code(hospital.getCode())
                .city(hospital.getCity())
                .active(hospital.isActive())
                .build())
            .toList();
    }
}