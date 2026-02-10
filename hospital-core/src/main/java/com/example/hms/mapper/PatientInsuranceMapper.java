package com.example.hms.mapper;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PatientInsuranceMapper {

    public PatientInsuranceResponseDTO toPatientInsuranceResponseDTO(PatientInsurance insurance) {
        if (insurance == null) return null;

        UUID hospitalId = null;
        UUID assignmentId = null;

        UserRoleHospitalAssignment assignment = insurance.getAssignment();
        if (assignment != null) {
            assignmentId = assignment.getId();
            if (assignment.getHospital() != null) {
                hospitalId = assignment.getHospital().getId();
            }
        }

        return PatientInsuranceResponseDTO.builder()
            .id(insurance.getId())
            .patientId(insurance.getPatient() != null ? insurance.getPatient().getId() : null)
            .hospitalId(hospitalId)
            .assignmentId(assignmentId)
            .linkedByUserId(insurance.getLinkedByUserId())
            .linkedAs(insurance.getLinkedAs()) // <-- String already, remove .name()
            .providerName(insurance.getProviderName())
            .policyNumber(insurance.getPolicyNumber())
            .groupNumber(insurance.getGroupNumber())
            .subscriberName(insurance.getSubscriberName())
            .subscriberRelationship(insurance.getSubscriberRelationship())
            .effectiveDate(insurance.getEffectiveDate())
            .expirationDate(insurance.getExpirationDate())
        .isPrimary(insurance.isPrimary())
            .createdAt(insurance.getCreatedAt())
            .updatedAt(insurance.getUpdatedAt())
            .build();
    }

    public PatientInsurance toPatientInsurance(PatientInsuranceRequestDTO dto, Patient patient) {
        if (dto == null) return null;

        return PatientInsurance.builder()
            .patient(patient)
            .providerName(trim(dto.getProviderName()))
            .policyNumber(trim(dto.getPolicyNumber()))
            .groupNumber(trim(dto.getGroupNumber()))
            .subscriberName(trim(dto.getSubscriberName()))
            .subscriberRelationship(trim(dto.getSubscriberRelationship()))
            .effectiveDate(dto.getEffectiveDate())
            .expirationDate(dto.getExpirationDate())
            .build();
    }

    public void updateEntityFromDto(PatientInsurance entity, PatientInsuranceRequestDTO dto, Patient patient) {
        if (entity == null || dto == null) return;

        entity.setPatient(patient);
        entity.setProviderName(trim(dto.getProviderName()));
        entity.setPolicyNumber(trim(dto.getPolicyNumber()));
        entity.setGroupNumber(trim(dto.getGroupNumber()));
        entity.setSubscriberName(trim(dto.getSubscriberName()));
        entity.setSubscriberRelationship(trim(dto.getSubscriberRelationship()));
        entity.setEffectiveDate(dto.getEffectiveDate());
        entity.setExpirationDate(dto.getExpirationDate());
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}

