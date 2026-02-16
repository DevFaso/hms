package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PatientAllergyRequestDTO;
import com.example.hms.payload.dto.PatientAllergyResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PatientAllergyMapper {

    public PatientAllergyResponseDTO toResponseDto(PatientAllergy allergy) {
        if (allergy == null) {
            return null;
        }

        Hospital hospital = allergy.getHospital();
        Staff staff = allergy.getRecordedBy();

        String recordedBy = resolveStaffName(staff);

        return PatientAllergyResponseDTO.builder()
            .id(allergy.getId())
            .patientId(allergy.getPatient() != null ? allergy.getPatient().getId() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .allergenDisplay(allergy.getAllergenDisplay())
            .allergenCode(allergy.getAllergenCode())
            .category(allergy.getCategory())
            .severity(allergy.getSeverity() != null ? allergy.getSeverity().name() : null)
            .verificationStatus(allergy.getVerificationStatus() != null ? allergy.getVerificationStatus().name() : null)
            .reaction(allergy.getReaction())
            .reactionNotes(allergy.getReactionNotes())
            .onsetDate(allergy.getOnsetDate())
            .lastOccurrenceDate(allergy.getLastOccurrenceDate())
            .recordedDate(allergy.getRecordedDate())
            .active(allergy.isActive())
            .recordedBy(recordedBy)
            .sourceSystem(allergy.getSourceSystem())
            .createdAt(allergy.getCreatedAt())
            .updatedAt(allergy.getUpdatedAt())
            .build();
    }

    public void updateEntityFromRequest(PatientAllergyRequestDTO request, PatientAllergy allergy) {
        if (request == null || allergy == null) {
            return;
        }

        allergy.setAllergenDisplay(trimOrNull(request.getAllergenDisplay()));
        allergy.setAllergenCode(trimOrNull(request.getAllergenCode()));
        allergy.setCategory(trimOrNull(request.getCategory()));
        allergy.setSeverity(request.getSeverity());
        allergy.setVerificationStatus(request.getVerificationStatus());
        allergy.setReaction(trimOrNull(request.getReaction()));
        allergy.setReactionNotes(trimOrNull(request.getReactionNotes()));
        allergy.setOnsetDate(request.getOnsetDate());
        allergy.setLastOccurrenceDate(request.getLastOccurrenceDate());
        allergy.setRecordedDate(request.getRecordedDate());
        allergy.setSourceSystem(trimOrNull(request.getSourceSystem()));

        if (request.getActive() != null) {
            allergy.setActive(request.getActive());
        }
    }

    private String resolveStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName().trim();
        }
        if (staff.getUser() != null) {
            String first = safeTrim(staff.getUser().getFirstName());
            String last = safeTrim(staff.getUser().getLastName());
            String combined = (first + " " + last).trim();
            if (!combined.isEmpty()) {
                return combined;
            }
        }
        return null;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
