package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class SocialHistoryMapper {

    public SocialHistoryResponseDTO toResponseDTO(PatientSocialHistory entity) {
        if (entity == null) {
            return null;
        }

        return SocialHistoryResponseDTO.builder()
                .id(entity.getId())
                .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
                .patientName(entity.getPatient() != null ? entity.getPatient().getFullName() : null)
                .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
                .hospitalName(entity.getHospital() != null ? entity.getHospital().getName() : null)
                .recordedByStaffId(entity.getRecordedBy() != null ? entity.getRecordedBy().getId() : null)
                .recordedByName(entity.getRecordedBy() != null ? entity.getRecordedBy().getFullName() : null)
                .recordedDate(entity.getRecordedDate())
                // Tobacco
                .tobaccoUse(entity.getTobaccoUse())
                .tobaccoType(entity.getTobaccoType())
                .tobaccoPacksPerDay(entity.getTobaccoPacksPerDay())
                .tobaccoYearsUsed(entity.getTobaccoYearsUsed())
                .tobaccoQuitDate(entity.getTobaccoQuitDate())
                .tobaccoNotes(entity.getTobaccoNotes())
                // Alcohol
                .alcoholUse(entity.getAlcoholUse())
                .alcoholFrequency(entity.getAlcoholFrequency())
                .alcoholDrinksPerWeek(entity.getAlcoholDrinksPerWeek())
                .alcoholBingeDrinking(entity.getAlcoholBingeDrinking())
                .alcoholNotes(entity.getAlcoholNotes())
                // Substance
                .recreationalDrugUse(entity.getRecreationalDrugUse())
                .drugTypesUsed(entity.getDrugTypesUsed())
                .intravenousDrugUse(entity.getIntravenousDrugUse())
                .substanceAbuseTreatment(entity.getSubstanceAbuseTreatment())
                .substanceNotes(entity.getSubstanceNotes())
                // Exercise
                .exerciseFrequency(entity.getExerciseFrequency())
                .exerciseType(entity.getExerciseType())
                .exerciseMinutesPerWeek(entity.getExerciseMinutesPerWeek())
                // Diet
                .dietType(entity.getDietType())
                .dietRestrictions(entity.getDietRestrictions())
                .nutritionalConcerns(entity.getNutritionalConcerns())
                // Occupation
                .occupation(entity.getOccupation())
                .employmentStatus(entity.getEmploymentStatus())
                .occupationalHazards(entity.getOccupationalHazards())
                // Living
                .maritalStatus(entity.getMaritalStatus())
                .livingArrangement(entity.getLivingArrangement())
                .housingStability(entity.getHousingStability())
                .householdMembers(entity.getHouseholdMembers())
                // Social Support
                .hasPrimaryCaregiver(entity.getHasPrimaryCaregiver())
                .socialSupportNetwork(entity.getSocialSupportNetwork())
                .socialIsolationRisk(entity.getSocialIsolationRisk())
                // Education
                .educationLevel(entity.getEducationLevel())
                .healthLiteracyConcerns(entity.getHealthLiteracyConcerns())
                .preferredLanguage(entity.getPreferredLanguage())
                .interpreterNeeded(entity.getInterpreterNeeded())
                // Financial
                .insuranceStatus(entity.getInsuranceStatus())
                .financialBarriers(entity.getFinancialBarriers())
                .transportationAccess(entity.getTransportationAccess())
                // Sexual Health
                .sexuallyActive(entity.getSexuallyActive())
                .numberOfPartners(entity.getNumberOfPartners())
                .contraceptionUse(entity.getContraceptionUse())
                .stiHistory(entity.getStiHistory())
                .sexualHealthNotes(entity.getSexualHealthNotes())
                // Mental Health
                .stressLevel(entity.getStressLevel())
                .stressSources(entity.getStressSources())
                .copingMechanisms(entity.getCopingMechanisms())
                .mentalHealthSupport(entity.getMentalHealthSupport())
                // Safety
                .domesticViolenceScreening(entity.getDomesticViolenceScreening())
                .feelsSafeAtHome(entity.getFeelsSafeAtHome())
                .abuseHistory(entity.getAbuseHistory())
                .safetyConcerns(entity.getSafetyConcerns())
                // Metadata
                .versionNumber(entity.getVersionNumber())
                .active(entity.getActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PatientSocialHistory toEntity(SocialHistoryRequestDTO dto, Patient patient, 
                                         Hospital hospital, Staff recordedBy) {
        if (dto == null) {
            return null;
        }

        return PatientSocialHistory.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(recordedBy)
                .recordedDate(dto.getRecordedDate())
                // Tobacco
                .tobaccoUse(dto.getTobaccoUse())
                .tobaccoType(dto.getTobaccoType())
                .tobaccoPacksPerDay(dto.getTobaccoPacksPerDay())
                .tobaccoYearsUsed(dto.getTobaccoYearsUsed())
                .tobaccoQuitDate(dto.getTobaccoQuitDate())
                .tobaccoNotes(dto.getTobaccoNotes())
                // Alcohol
                .alcoholUse(dto.getAlcoholUse())
                .alcoholFrequency(dto.getAlcoholFrequency())
                .alcoholDrinksPerWeek(dto.getAlcoholDrinksPerWeek())
                .alcoholBingeDrinking(dto.getAlcoholBingeDrinking())
                .alcoholNotes(dto.getAlcoholNotes())
                // Substance
                .recreationalDrugUse(dto.getRecreationalDrugUse())
                .drugTypesUsed(dto.getDrugTypesUsed())
                .intravenousDrugUse(dto.getIntravenousDrugUse())
                .substanceAbuseTreatment(dto.getSubstanceAbuseTreatment())
                .substanceNotes(dto.getSubstanceNotes())
                // Exercise
                .exerciseFrequency(dto.getExerciseFrequency())
                .exerciseType(dto.getExerciseType())
                .exerciseMinutesPerWeek(dto.getExerciseMinutesPerWeek())
                // Diet
                .dietType(dto.getDietType())
                .dietRestrictions(dto.getDietRestrictions())
                .nutritionalConcerns(dto.getNutritionalConcerns())
                // Occupation
                .occupation(dto.getOccupation())
                .employmentStatus(dto.getEmploymentStatus())
                .occupationalHazards(dto.getOccupationalHazards())
                // Living
                .maritalStatus(dto.getMaritalStatus())
                .livingArrangement(dto.getLivingArrangement())
                .housingStability(dto.getHousingStability())
                .householdMembers(dto.getHouseholdMembers())
                // Social Support
                .hasPrimaryCaregiver(dto.getHasPrimaryCaregiver())
                .socialSupportNetwork(dto.getSocialSupportNetwork())
                .socialIsolationRisk(dto.getSocialIsolationRisk())
                // Education
                .educationLevel(dto.getEducationLevel())
                .healthLiteracyConcerns(dto.getHealthLiteracyConcerns())
                .preferredLanguage(dto.getPreferredLanguage())
                .interpreterNeeded(dto.getInterpreterNeeded())
                // Financial
                .insuranceStatus(dto.getInsuranceStatus())
                .financialBarriers(dto.getFinancialBarriers())
                .transportationAccess(dto.getTransportationAccess())
                // Sexual Health
                .sexuallyActive(dto.getSexuallyActive())
                .numberOfPartners(dto.getNumberOfPartners())
                .contraceptionUse(dto.getContraceptionUse())
                .stiHistory(dto.getStiHistory())
                .sexualHealthNotes(dto.getSexualHealthNotes())
                // Mental Health
                .stressLevel(dto.getStressLevel())
                .stressSources(dto.getStressSources())
                .copingMechanisms(dto.getCopingMechanisms())
                .mentalHealthSupport(dto.getMentalHealthSupport())
                // Safety
                .domesticViolenceScreening(dto.getDomesticViolenceScreening())
                .feelsSafeAtHome(dto.getFeelsSafeAtHome())
                .abuseHistory(dto.getAbuseHistory())
                .safetyConcerns(dto.getSafetyConcerns())
                // Metadata
                .versionNumber(dto.getVersionNumber() != null ? dto.getVersionNumber() : 1)
                .active(Objects.requireNonNullElse(dto.getActive(), Boolean.TRUE))
                .build();
    }

    public void updateEntity(PatientSocialHistory entity, SocialHistoryRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setRecordedDate(dto.getRecordedDate());
        // Tobacco
        entity.setTobaccoUse(dto.getTobaccoUse());
        entity.setTobaccoType(dto.getTobaccoType());
        entity.setTobaccoPacksPerDay(dto.getTobaccoPacksPerDay());
        entity.setTobaccoYearsUsed(dto.getTobaccoYearsUsed());
        entity.setTobaccoQuitDate(dto.getTobaccoQuitDate());
        entity.setTobaccoNotes(dto.getTobaccoNotes());
        // Alcohol
        entity.setAlcoholUse(dto.getAlcoholUse());
        entity.setAlcoholFrequency(dto.getAlcoholFrequency());
        entity.setAlcoholDrinksPerWeek(dto.getAlcoholDrinksPerWeek());
        entity.setAlcoholBingeDrinking(dto.getAlcoholBingeDrinking());
        entity.setAlcoholNotes(dto.getAlcoholNotes());
        // Substance
        entity.setRecreationalDrugUse(dto.getRecreationalDrugUse());
        entity.setDrugTypesUsed(dto.getDrugTypesUsed());
        entity.setIntravenousDrugUse(dto.getIntravenousDrugUse());
    entity.setSubstanceAbuseTreatment(dto.getSubstanceAbuseTreatment());
    entity.setSubstanceNotes(dto.getSubstanceNotes());
        // Exercise
        entity.setExerciseFrequency(dto.getExerciseFrequency());
        entity.setExerciseType(dto.getExerciseType());
        entity.setExerciseMinutesPerWeek(dto.getExerciseMinutesPerWeek());
        // Diet
    entity.setDietType(dto.getDietType());
    entity.setDietRestrictions(dto.getDietRestrictions());
        entity.setNutritionalConcerns(dto.getNutritionalConcerns());
        // Occupation
        entity.setOccupation(dto.getOccupation());
        entity.setEmploymentStatus(dto.getEmploymentStatus());
        entity.setOccupationalHazards(dto.getOccupationalHazards());
        // Living
        entity.setMaritalStatus(dto.getMaritalStatus());
        entity.setLivingArrangement(dto.getLivingArrangement());
        entity.setHousingStability(dto.getHousingStability());
        entity.setHouseholdMembers(dto.getHouseholdMembers());
        // Social Support
        entity.setHasPrimaryCaregiver(dto.getHasPrimaryCaregiver());
        entity.setSocialSupportNetwork(dto.getSocialSupportNetwork());
        entity.setSocialIsolationRisk(dto.getSocialIsolationRisk());
        // Education
        entity.setEducationLevel(dto.getEducationLevel());
        entity.setHealthLiteracyConcerns(dto.getHealthLiteracyConcerns());
        entity.setPreferredLanguage(dto.getPreferredLanguage());
        entity.setInterpreterNeeded(dto.getInterpreterNeeded());
        // Financial
        entity.setInsuranceStatus(dto.getInsuranceStatus());
        entity.setFinancialBarriers(dto.getFinancialBarriers());
        entity.setTransportationAccess(dto.getTransportationAccess());
        // Sexual Health
        entity.setSexuallyActive(dto.getSexuallyActive());
        entity.setNumberOfPartners(dto.getNumberOfPartners());
        entity.setContraceptionUse(dto.getContraceptionUse());
        entity.setStiHistory(dto.getStiHistory());
        entity.setSexualHealthNotes(dto.getSexualHealthNotes());
        // Mental Health
        entity.setStressLevel(dto.getStressLevel());
        entity.setStressSources(dto.getStressSources());
        entity.setCopingMechanisms(dto.getCopingMechanisms());
        entity.setMentalHealthSupport(dto.getMentalHealthSupport());
        // Safety
        entity.setDomesticViolenceScreening(dto.getDomesticViolenceScreening());
        entity.setFeelsSafeAtHome(dto.getFeelsSafeAtHome());
        entity.setAbuseHistory(dto.getAbuseHistory());
        entity.setSafetyConcerns(dto.getSafetyConcerns());
        // Metadata
        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }
}
