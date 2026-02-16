package com.example.hms.mapper;

import com.example.hms.model.MaternalHistory;
import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import org.springframework.stereotype.Component;

/**
 * Mapper for Maternal History entity and DTOs.
 * Handles bidirectional mapping between entity and request/response DTOs.
 */
@Component
public class MaternalHistoryMapper {

    /**
     * Convert Maternal History entity to Response DTO.
     */
    public MaternalHistoryResponseDTO toResponseDTO(MaternalHistory entity) {
        if (entity == null) {
            return null;
        }

        return MaternalHistoryResponseDTO.builder()
            .id(entity.getId())
            .patientId(entity.getPatient() != null ? entity.getPatient().getId() : null)
            .hospitalId(entity.getHospital() != null ? entity.getHospital().getId() : null)
            .recordedByStaffId(entity.getRecordedBy() != null ? entity.getRecordedBy().getId() : null)
            .recordedDate(entity.getRecordedDate())
            .versionNumber(entity.getVersionNumber())
            .updateReason(entity.getUpdateReason())
            // Map all sections
            .menstrualHistory(mapMenstrualHistoryToDTO(entity))
            .obstetricHistory(mapObstetricHistoryToDTO(entity))
            .complicationsHistory(mapComplicationsHistoryToDTO(entity))
            .medicalHistory(mapMedicalHistoryToDTO(entity))
            .medicationsImmunizations(mapMedicationsImmunizationsToDTO(entity))
            .familyHistory(mapFamilyHistoryToDTO(entity))
            .lifestyleFactors(mapLifestyleFactorsToDTO(entity))
            .psychosocialFactors(mapPsychosocialFactorsToDTO(entity))
            // Clinical assessment
            .clinicalNotes(entity.getClinicalNotes())
            .dataComplete(entity.getDataComplete())
            .reviewedByProvider(entity.getReviewedByProvider())
            .reviewedByStaffId(entity.getReviewedByStaffId())
            .reviewTimestamp(entity.getReviewTimestamp())
            .requiresSpecialistReferral(entity.getRequiresSpecialistReferral())
            .specialistReferralReason(entity.getSpecialistReferralReason())
            // Risk assessment
            .calculatedRiskScore(entity.getCalculatedRiskScore())
            .riskCategory(entity.getRiskCategory())
            .identifiedRiskFactors(entity.getIdentifiedRiskFactors())
            .additionalNotes(entity.getAdditionalNotes())
            // Audit
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            // Convenience flags
            .hasHighRiskHistory(entity.hasHighRiskHistory())
            .hasChronicMedicalConditions(entity.hasChronicMedicalConditions())
            .hasLifestyleRiskFactors(entity.hasLifestyleRiskFactors())
            .needsPsychosocialSupport(entity.needsPsychosocialSupport())
            .build();
    }

    /**
     * Update Maternal History entity from Request DTO.
     */
    public void updateEntityFromRequest(MaternalHistory entity, MaternalHistoryRequestDTO request) {
        if (request == null) {
            return;
        }

        entity.setRecordedDate(request.getRecordedDate());
        entity.setUpdateReason(request.getUpdateReason());

        // Map all sections
        updateMenstrualHistoryFromDTO(entity, request.getMenstrualHistory());
        updateObstetricHistoryFromDTO(entity, request.getObstetricHistory());
        updateComplicationsHistoryFromDTO(entity, request.getComplicationsHistory());
        updateMedicalHistoryFromDTO(entity, request.getMedicalHistory());
        updateMedicationsImmunizationsFromDTO(entity, request.getMedicationsImmunizations());
        updateFamilyHistoryFromDTO(entity, request.getFamilyHistory());
        updateLifestyleFactorsFromDTO(entity, request.getLifestyleFactors());
        updatePsychosocialFactorsFromDTO(entity, request.getPsychosocialFactors());

        // Clinical assessment
        entity.setClinicalNotes(request.getClinicalNotes());
        entity.setDataComplete(request.getDataComplete());
        entity.setRequiresSpecialistReferral(request.getRequiresSpecialistReferral());
        entity.setSpecialistReferralReason(request.getSpecialistReferralReason());
    }

    // ===== Private mapping methods for nested DTOs =====

    private MaternalHistoryRequestDTO.MenstrualHistoryDTO mapMenstrualHistoryToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.MenstrualHistoryDTO.builder()
            .lastMenstrualPeriod(entity.getLastMenstrualPeriod())
            .estimatedDueDate(entity.getEstimatedDueDate())
            .estimatedDueDateByUltrasound(entity.getEstimatedDueDateByUltrasound())
            .ultrasoundConfirmationDate(entity.getUltrasoundConfirmationDate())
            .menstrualCycleLengthDays(entity.getMenstrualCycleLengthDays())
            .menstrualCycleRegularity(entity.getMenstrualCycleRegularity())
            .contraceptionMethodPrior(entity.getContraceptionMethodPrior())
            .build();
    }

    private void updateMenstrualHistoryFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.MenstrualHistoryDTO dto) {
        if (dto == null) return;
        entity.setLastMenstrualPeriod(dto.getLastMenstrualPeriod());
        entity.setEstimatedDueDate(dto.getEstimatedDueDate());
        entity.setEstimatedDueDateByUltrasound(dto.getEstimatedDueDateByUltrasound());
        entity.setUltrasoundConfirmationDate(dto.getUltrasoundConfirmationDate());
        entity.setMenstrualCycleLengthDays(dto.getMenstrualCycleLengthDays());
        entity.setMenstrualCycleRegularity(dto.getMenstrualCycleRegularity());
        entity.setContraceptionMethodPrior(dto.getContraceptionMethodPrior());
    }

    private MaternalHistoryRequestDTO.ObstetricHistoryDTO mapObstetricHistoryToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.ObstetricHistoryDTO.builder()
            .gravida(entity.getGravida())
            .para(entity.getPara())
            .termBirths(entity.getTermBirths())
            .pretermBirths(entity.getPretermBirths())
            .abortions(entity.getAbortions())
            .livingChildren(entity.getLivingChildren())
            .previousCesareanSections(entity.getPreviousCesareanSections())
            .previousPregnancyOutcomes(entity.getPreviousPregnancyOutcomes())
            .previousPregnancyComplications(entity.getPreviousPregnancyComplications())
            .build();
    }

    private void updateObstetricHistoryFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.ObstetricHistoryDTO dto) {
        if (dto == null) return;
        entity.setGravida(dto.getGravida());
        entity.setPara(dto.getPara());
        entity.setTermBirths(dto.getTermBirths());
        entity.setPretermBirths(dto.getPretermBirths());
        entity.setAbortions(dto.getAbortions());
        entity.setLivingChildren(dto.getLivingChildren());
        entity.setPreviousCesareanSections(dto.getPreviousCesareanSections());
        entity.setPreviousPregnancyOutcomes(dto.getPreviousPregnancyOutcomes());
        entity.setPreviousPregnancyComplications(dto.getPreviousPregnancyComplications());
    }

    private MaternalHistoryRequestDTO.ComplicationsHistoryDTO mapComplicationsHistoryToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.ComplicationsHistoryDTO.builder()
            .gestationalDiabetesHistory(entity.getGestationalDiabetesHistory())
            .preeclampsiaHistory(entity.getPreeclampsiaHistory())
            .eclampsiaHistory(entity.getEclampsiaHistory())
            .hellpSyndromeHistory(entity.getHellpSyndromeHistory())
            .pretermLaborHistory(entity.getPretermLaborHistory())
            .postpartumHemorrhageHistory(entity.getPostpartumHemorrhageHistory())
            .placentaPreviaHistory(entity.getPlacentaPreviaHistory())
            .placentalAbruptionHistory(entity.getPlacentalAbruptionHistory())
            .fetalAnomalyHistory(entity.getFetalAnomalyHistory())
            .complicationsDetails(entity.getComplicationsDetails())
            .build();
    }

    private void updateComplicationsHistoryFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.ComplicationsHistoryDTO dto) {
        if (dto == null) return;
        entity.setGestationalDiabetesHistory(dto.getGestationalDiabetesHistory());
        entity.setPreeclampsiaHistory(dto.getPreeclampsiaHistory());
        entity.setEclampsiaHistory(dto.getEclampsiaHistory());
        entity.setHellpSyndromeHistory(dto.getHellpSyndromeHistory());
        entity.setPretermLaborHistory(dto.getPretermLaborHistory());
        entity.setPostpartumHemorrhageHistory(dto.getPostpartumHemorrhageHistory());
        entity.setPlacentaPreviaHistory(dto.getPlacentaPreviaHistory());
        entity.setPlacentalAbruptionHistory(dto.getPlacentalAbruptionHistory());
        entity.setFetalAnomalyHistory(dto.getFetalAnomalyHistory());
        entity.setComplicationsDetails(dto.getComplicationsDetails());
    }

    private MaternalHistoryRequestDTO.MedicalHistoryDTO mapMedicalHistoryToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.MedicalHistoryDTO.builder()
            .chronicConditions(entity.getChronicConditions())
            .diabetes(entity.getDiabetes())
            .hypertension(entity.getHypertension())
            .thyroidDisorder(entity.getThyroidDisorder())
            .cardiacDisease(entity.getCardiacDisease())
            .renalDisease(entity.getRenalDisease())
            .autoimmuneDisorder(entity.getAutoimmuneDisorder())
            .mentalHealthConditions(entity.getMentalHealthConditions())
            .surgicalHistory(entity.getSurgicalHistory())
            .previousAbdominalSurgery(entity.getPreviousAbdominalSurgery())
            .previousUterineSurgery(entity.getPreviousUterineSurgery())
            .allergies(entity.getAllergies())
            .drugAllergies(entity.getDrugAllergies())
            .latexAllergy(entity.getLatexAllergy())
            .build();
    }

    private void updateMedicalHistoryFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.MedicalHistoryDTO dto) {
        if (dto == null) return;
        entity.setChronicConditions(dto.getChronicConditions());
        entity.setDiabetes(dto.getDiabetes());
        entity.setHypertension(dto.getHypertension());
        entity.setThyroidDisorder(dto.getThyroidDisorder());
        entity.setCardiacDisease(dto.getCardiacDisease());
        entity.setRenalDisease(dto.getRenalDisease());
        entity.setAutoimmuneDisorder(dto.getAutoimmuneDisorder());
        entity.setMentalHealthConditions(dto.getMentalHealthConditions());
        entity.setSurgicalHistory(dto.getSurgicalHistory());
        entity.setPreviousAbdominalSurgery(dto.getPreviousAbdominalSurgery());
        entity.setPreviousUterineSurgery(dto.getPreviousUterineSurgery());
        entity.setAllergies(dto.getAllergies());
        entity.setDrugAllergies(dto.getDrugAllergies());
        entity.setLatexAllergy(dto.getLatexAllergy());
    }

    private MaternalHistoryRequestDTO.MedicationsImmunizationsDTO mapMedicationsImmunizationsToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.MedicationsImmunizationsDTO.builder()
            .currentMedications(entity.getCurrentMedications())
            .prenatalVitaminsStarted(entity.getPrenatalVitaminsStarted())
            .prenatalVitaminsStartDate(entity.getPrenatalVitaminsStartDate())
            .folicAcidSupplementation(entity.getFolicAcidSupplementation())
            .rubellaImmunity(entity.getRubellaImmunity())
            .varicellaImmunity(entity.getVaricellaImmunity())
            .hepatitisBVaccination(entity.getHepatitisBVaccination())
            .tdapVaccination(entity.getTdapVaccination())
            .tdapVaccinationDate(entity.getTdapVaccinationDate())
            .fluVaccinationCurrentSeason(entity.getFluVaccinationCurrentSeason())
            .fluVaccinationDate(entity.getFluVaccinationDate())
            .covid19Vaccination(entity.getCovid19Vaccination())
            .immunizationNotes(entity.getImmunizationNotes())
            .build();
    }

    private void updateMedicationsImmunizationsFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.MedicationsImmunizationsDTO dto) {
        if (dto == null) return;
        entity.setCurrentMedications(dto.getCurrentMedications());
        entity.setPrenatalVitaminsStarted(dto.getPrenatalVitaminsStarted());
        entity.setPrenatalVitaminsStartDate(dto.getPrenatalVitaminsStartDate());
        entity.setFolicAcidSupplementation(dto.getFolicAcidSupplementation());
        entity.setRubellaImmunity(dto.getRubellaImmunity());
        entity.setVaricellaImmunity(dto.getVaricellaImmunity());
        entity.setHepatitisBVaccination(dto.getHepatitisBVaccination());
        entity.setTdapVaccination(dto.getTdapVaccination());
        entity.setTdapVaccinationDate(dto.getTdapVaccinationDate());
        entity.setFluVaccinationCurrentSeason(dto.getFluVaccinationCurrentSeason());
        entity.setFluVaccinationDate(dto.getFluVaccinationDate());
        entity.setCovid19Vaccination(dto.getCovid19Vaccination());
        entity.setImmunizationNotes(dto.getImmunizationNotes());
    }

    private MaternalHistoryRequestDTO.FamilyHistoryDTO mapFamilyHistoryToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.FamilyHistoryDTO.builder()
            .familyMedicalHistory(entity.getFamilyMedicalHistory())
            .familyGeneticDisorders(entity.getFamilyGeneticDisorders())
            .familyPregnancyComplications(entity.getFamilyPregnancyComplications())
            .familyDiabetes(entity.getFamilyDiabetes())
            .familyHypertension(entity.getFamilyHypertension())
            .familyTwinHistory(entity.getFamilyTwinHistory())
            .familyHistoryDetails(entity.getFamilyHistoryDetails())
            .build();
    }

    private void updateFamilyHistoryFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.FamilyHistoryDTO dto) {
        if (dto == null) return;
        entity.setFamilyMedicalHistory(dto.getFamilyMedicalHistory());
        entity.setFamilyGeneticDisorders(dto.getFamilyGeneticDisorders());
        entity.setFamilyPregnancyComplications(dto.getFamilyPregnancyComplications());
        entity.setFamilyDiabetes(dto.getFamilyDiabetes());
        entity.setFamilyHypertension(dto.getFamilyHypertension());
        entity.setFamilyTwinHistory(dto.getFamilyTwinHistory());
        entity.setFamilyHistoryDetails(dto.getFamilyHistoryDetails());
    }

    private MaternalHistoryRequestDTO.LifestyleFactorsDTO mapLifestyleFactorsToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.LifestyleFactorsDTO.builder()
            .smokingStatus(entity.getSmokingStatus())
            .cigarettesPerDay(entity.getCigarettesPerDay())
            .smokingCessationDate(entity.getSmokingCessationDate())
            .alcoholUse(entity.getAlcoholUse())
            .alcoholUseDetails(entity.getAlcoholUseDetails())
            .substanceUse(entity.getSubstanceUse())
            .recreationalDrugUse(entity.getRecreationalDrugUse())
            .substanceUseDetails(entity.getSubstanceUseDetails())
            .caffeineIntakeMgDaily(entity.getCaffeineIntakeMgDaily())
            .dietType(entity.getDietType())
            .dietDescription(entity.getDietDescription())
            .exerciseFrequency(entity.getExerciseFrequency())
            .exerciseDetails(entity.getExerciseDetails())
            .occupationalHazards(entity.getOccupationalHazards())
            .occupationalHazardsDetails(entity.getOccupationalHazardsDetails())
            .environmentalExposures(entity.getEnvironmentalExposures())
            .petExposure(entity.getPetExposure())
            .travelHistory(entity.getTravelHistory())
            .zikaRiskExposure(entity.getZikaRiskExposure())
            .build();
    }

    private void updateLifestyleFactorsFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.LifestyleFactorsDTO dto) {
        if (dto == null) return;
        entity.setSmokingStatus(dto.getSmokingStatus());
        entity.setCigarettesPerDay(dto.getCigarettesPerDay());
        entity.setSmokingCessationDate(dto.getSmokingCessationDate());
        entity.setAlcoholUse(dto.getAlcoholUse());
        entity.setAlcoholUseDetails(dto.getAlcoholUseDetails());
        entity.setSubstanceUse(dto.getSubstanceUse());
        entity.setRecreationalDrugUse(dto.getRecreationalDrugUse());
        entity.setSubstanceUseDetails(dto.getSubstanceUseDetails());
        entity.setCaffeineIntakeMgDaily(dto.getCaffeineIntakeMgDaily());
        entity.setDietType(dto.getDietType());
        entity.setDietDescription(dto.getDietDescription());
        entity.setExerciseFrequency(dto.getExerciseFrequency());
        entity.setExerciseDetails(dto.getExerciseDetails());
        entity.setOccupationalHazards(dto.getOccupationalHazards());
        entity.setOccupationalHazardsDetails(dto.getOccupationalHazardsDetails());
        entity.setEnvironmentalExposures(dto.getEnvironmentalExposures());
        entity.setPetExposure(dto.getPetExposure());
        entity.setTravelHistory(dto.getTravelHistory());
        entity.setZikaRiskExposure(dto.getZikaRiskExposure());
    }

    private MaternalHistoryRequestDTO.PsychosocialFactorsDTO mapPsychosocialFactorsToDTO(MaternalHistory entity) {
        return MaternalHistoryRequestDTO.PsychosocialFactorsDTO.builder()
            .mentalHealthScreeningCompleted(entity.getMentalHealthScreeningCompleted())
            .depressionScreeningScore(entity.getDepressionScreeningScore())
            .anxietyPresent(entity.getAnxietyPresent())
            .domesticViolenceScreening(entity.getDomesticViolenceScreening())
            .domesticViolenceConcerns(entity.getDomesticViolenceConcerns())
            .domesticViolenceDetails(entity.getDomesticViolenceDetails())
            .supportSystem(entity.getSupportSystem())
            .adequateHousing(entity.getAdequateHousing())
            .foodSecurity(entity.getFoodSecurity())
            .financialConcerns(entity.getFinancialConcerns())
            .psychosocialNotes(entity.getPsychosocialNotes())
            .build();
    }

    private void updatePsychosocialFactorsFromDTO(MaternalHistory entity, MaternalHistoryRequestDTO.PsychosocialFactorsDTO dto) {
        if (dto == null) return;
        entity.setMentalHealthScreeningCompleted(dto.getMentalHealthScreeningCompleted());
        entity.setDepressionScreeningScore(dto.getDepressionScreeningScore());
        entity.setAnxietyPresent(dto.getAnxietyPresent());
        entity.setDomesticViolenceScreening(dto.getDomesticViolenceScreening());
        entity.setDomesticViolenceConcerns(dto.getDomesticViolenceConcerns());
        entity.setDomesticViolenceDetails(dto.getDomesticViolenceDetails());
        entity.setSupportSystem(dto.getSupportSystem());
        entity.setAdequateHousing(dto.getAdequateHousing());
        entity.setFoodSecurity(dto.getFoodSecurity());
        entity.setFinancialConcerns(dto.getFinancialConcerns());
        entity.setPsychosocialNotes(dto.getPsychosocialNotes());
    }
}
