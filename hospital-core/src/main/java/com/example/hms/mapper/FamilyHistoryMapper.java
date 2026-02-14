package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientFamilyHistory;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class FamilyHistoryMapper {

    public FamilyHistoryResponseDTO toResponseDTO(PatientFamilyHistory entity) {
        if (entity == null) {
            return null;
        }

        return FamilyHistoryResponseDTO.builder()
                .id(entity.getId())
                .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
                .patientName(entity.getPatient() != null ? entity.getPatient().getFullName() : null)
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .recordedByStaffId(entity.getRecordedBy() != null ? entity.getRecordedBy().getId() : null)
                .recordedByName(entity.getRecordedBy() != null ? entity.getRecordedBy().getFullName() : null)
                .recordedDate(entity.getRecordedDate())
                // Relationship
                .relationship(entity.getRelationship())
                .relationshipSide(entity.getRelationshipSide())
                .relativeName(entity.getRelativeName())
                .relativeGender(entity.getRelativeGender())
                .relativeLiving(entity.getRelativeLiving())
                .relativeAge(entity.getRelativeAge())
                .relativeAgeAtDeath(entity.getRelativeAgeAtDeath())
                .causeOfDeath(entity.getCauseOfDeath())
                // Condition
                .conditionCode(entity.getConditionCode())
                .conditionDisplay(entity.getConditionDisplay())
                .conditionCategory(entity.getConditionCategory())
                .ageAtOnset(entity.getAgeAtOnset())
                .severity(entity.getSeverity())
                .outcome(entity.getOutcome())
                // Genetic
                .geneticCondition(entity.getGeneticCondition())
                .geneticTestingDone(entity.getGeneticTestingDone())
                .geneticMarker(entity.getGeneticMarker())
                .inheritancePattern(entity.getInheritancePattern())
                // Clinical
                .clinicallySignificant(entity.getClinicallySignificant())
                .riskFactorForPatient(entity.getRiskFactorForPatient())
                .screeningRecommended(entity.getScreeningRecommended())
                .screeningType(entity.getScreeningType())
                .recommendedAgeForScreening(entity.getRecommendedAgeForScreening())
                // Flags
                .isCancer(entity.getIsCancer())
                .isCardiovascular(entity.getIsCardiovascular())
                .isDiabetes(entity.getIsDiabetes())
                .isMentalHealth(entity.getIsMentalHealth())
                .isNeurological(entity.getIsNeurological())
                .isAutoimmune(entity.getIsAutoimmune())
                // Additional
                .notes(entity.getNotes())
                .sourceOfInformation(entity.getSourceOfInformation())
                .verified(entity.getVerified())
                .verificationDate(entity.getVerificationDate())
                .active(entity.getActive())
                // Pedigree
                .generation(entity.getGeneration())
                .pedigreeId(entity.getPedigreeId())
                // Audit
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PatientFamilyHistory toEntity(FamilyHistoryRequestDTO dto, Patient patient,
                                         Hospital hospital, Staff recordedBy) {
        if (dto == null) {
            return null;
        }

        return PatientFamilyHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(recordedBy)
                .recordedDate(dto.getRecordedDate())
                // Relationship
                .relationship(dto.getRelationship())
                .relationshipSide(dto.getRelationshipSide())
                .relativeName(dto.getRelativeName())
                .relativeGender(dto.getRelativeGender())
                .relativeLiving(dto.getRelativeLiving())
                .relativeAge(dto.getRelativeAge())
                .relativeAgeAtDeath(dto.getRelativeAgeAtDeath())
                .causeOfDeath(dto.getCauseOfDeath())
                // Condition
                .conditionCode(dto.getConditionCode())
                .conditionDisplay(dto.getConditionDisplay())
                .conditionCategory(dto.getConditionCategory())
                .ageAtOnset(dto.getAgeAtOnset())
                .severity(dto.getSeverity())
                .outcome(dto.getOutcome())
                // Genetic
                .geneticCondition(dto.getGeneticCondition())
                .geneticTestingDone(dto.getGeneticTestingDone())
                .geneticMarker(dto.getGeneticMarker())
                .inheritancePattern(dto.getInheritancePattern())
                // Clinical
                .clinicallySignificant(dto.getClinicallySignificant())
                .riskFactorForPatient(dto.getRiskFactorForPatient())
                .screeningRecommended(dto.getScreeningRecommended())
                .screeningType(dto.getScreeningType())
                .recommendedAgeForScreening(dto.getRecommendedAgeForScreening())
                // Flags
                .isCancer(dto.getIsCancer())
                .isCardiovascular(dto.getIsCardiovascular())
                .isDiabetes(dto.getIsDiabetes())
                .isMentalHealth(dto.getIsMentalHealth())
                .isNeurological(dto.getIsNeurological())
                .isAutoimmune(dto.getIsAutoimmune())
                // Additional
                .notes(dto.getNotes())
                .sourceOfInformation(dto.getSourceOfInformation())
                .verified(dto.getVerified())
                .verificationDate(dto.getVerificationDate())
                .active(dto.getActive() == null || dto.getActive())
                // Pedigree
                .generation(dto.getGeneration())
                .pedigreeId(dto.getPedigreeId())
                .build();
    }

    public void updateEntity(PatientFamilyHistory entity, FamilyHistoryRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setRecordedDate(dto.getRecordedDate());
        // Relationship
        entity.setRelationship(dto.getRelationship());
        entity.setRelationshipSide(dto.getRelationshipSide());
        entity.setRelativeName(dto.getRelativeName());
        entity.setRelativeGender(dto.getRelativeGender());
        entity.setRelativeLiving(dto.getRelativeLiving());
        entity.setRelativeAge(dto.getRelativeAge());
        entity.setRelativeAgeAtDeath(dto.getRelativeAgeAtDeath());
        entity.setCauseOfDeath(dto.getCauseOfDeath());
        // Condition
        entity.setConditionCode(dto.getConditionCode());
        entity.setConditionDisplay(dto.getConditionDisplay());
        entity.setConditionCategory(dto.getConditionCategory());
        entity.setAgeAtOnset(dto.getAgeAtOnset());
        entity.setSeverity(dto.getSeverity());
        entity.setOutcome(dto.getOutcome());
        // Genetic
        entity.setGeneticCondition(dto.getGeneticCondition());
        entity.setGeneticTestingDone(dto.getGeneticTestingDone());
        entity.setGeneticMarker(dto.getGeneticMarker());
        entity.setInheritancePattern(dto.getInheritancePattern());
        // Clinical
        entity.setClinicallySignificant(dto.getClinicallySignificant());
        entity.setRiskFactorForPatient(dto.getRiskFactorForPatient());
        entity.setScreeningRecommended(dto.getScreeningRecommended());
        entity.setScreeningType(dto.getScreeningType());
        entity.setRecommendedAgeForScreening(dto.getRecommendedAgeForScreening());
        // Flags
        entity.setIsCancer(dto.getIsCancer());
        entity.setIsCardiovascular(dto.getIsCardiovascular());
        entity.setIsDiabetes(dto.getIsDiabetes());
        entity.setIsMentalHealth(dto.getIsMentalHealth());
        entity.setIsNeurological(dto.getIsNeurological());
        entity.setIsAutoimmune(dto.getIsAutoimmune());
        // Additional
        entity.setNotes(dto.getNotes());
        entity.setSourceOfInformation(dto.getSourceOfInformation());
        entity.setVerified(dto.getVerified());
        entity.setVerificationDate(dto.getVerificationDate());
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
        // Pedigree
        entity.setGeneration(dto.getGeneration());
        entity.setPedigreeId(dto.getPedigreeId());
    }
}
