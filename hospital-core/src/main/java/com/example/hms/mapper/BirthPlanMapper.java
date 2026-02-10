package com.example.hms.mapper;

import com.example.hms.model.BirthPlan;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Mapper for Birth Plan entity and DTOs.
 */
@Component
public class BirthPlanMapper {

    /**
     * Convert Birth Plan entity to Response DTO.
     */
    public BirthPlanResponseDTO toResponseDTO(BirthPlan birthPlan) {
        if (birthPlan == null) {
            return null;
        }

        return BirthPlanResponseDTO.builder()
            .id(birthPlan.getId())
            .patientId(birthPlan.getPatient() != null ? birthPlan.getPatient().getId() : null)
            .hospitalId(birthPlan.getHospital() != null ? birthPlan.getHospital().getId() : null)
            .introduction(mapIntroductionToResponse(birthPlan))
            .deliveryPreferences(mapDeliveryPreferencesToResponse(birthPlan))
            .painManagement(mapPainManagementToResponse(birthPlan))
            .deliveryRoomEnvironment(mapDeliveryRoomEnvironmentToResponse(birthPlan))
            .postpartumPreferences(mapPostpartumPreferencesToResponse(birthPlan))
            .additionalWishes(birthPlan.getAdditionalWishes())
            .flexibilityAcknowledgment(birthPlan.getFlexibilityAcknowledgment())
            .discussedWithProvider(birthPlan.getDiscussedWithProvider())
            .providerReviewRequired(birthPlan.getProviderReviewRequired())
            .providerReviewed(birthPlan.getProviderReviewed())
            .providerSignature(birthPlan.getProviderSignature())
            .providerSignatureDate(birthPlan.getProviderSignatureDate())
            .providerComments(birthPlan.getProviderComments())
            .createdAt(birthPlan.getCreatedAt())
            .updatedAt(birthPlan.getUpdatedAt())
            .build();
    }

    /**
     * Update Birth Plan entity from Request DTO (for create and update operations).
     */
    public void updateEntityFromRequest(BirthPlan birthPlan, BirthPlanRequestDTO request) {
        if (request == null) {
            return;
        }

        // Introduction
        if (request.getIntroduction() != null) {
            BirthPlanRequestDTO.IntroductionDTO intro = request.getIntroduction();
            birthPlan.setPatientName(intro.getPatientName());
            birthPlan.setExpectedDueDate(intro.getExpectedDueDate());
            birthPlan.setPlaceOfBirth(intro.getPlaceOfBirth());
            birthPlan.setHealthcareProvider(intro.getHealthcareProvider());
            birthPlan.setMedicalConditions(intro.getMedicalConditions());
        }

        // Delivery Preferences
        if (request.getDeliveryPreferences() != null) {
            BirthPlanRequestDTO.DeliveryPreferencesDTO delivery = request.getDeliveryPreferences();
            birthPlan.setPreferredDeliveryMethod(delivery.getPreferredDeliveryMethod());
            birthPlan.setBackupDeliveryMethod(delivery.getBackupDeliveryMethod());
            birthPlan.setDeliveryMethodNotes(delivery.getDeliveryMethodNotes());
        }

        // Pain Management
        if (request.getPainManagement() != null) {
            BirthPlanRequestDTO.PainManagementDTO pain = request.getPainManagement();
            birthPlan.setPreferredPainApproach(pain.getPreferredApproach());
            birthPlan.setUnmedicatedTechniques(
                pain.getUnmedicatedTechniques() != null ? new ArrayList<>(pain.getUnmedicatedTechniques()) : new ArrayList<>()
            );
            birthPlan.setMedicatedOptions(
                pain.getMedicatedOptions() != null ? new ArrayList<>(pain.getMedicatedOptions()) : new ArrayList<>()
            );
            birthPlan.setPainManagementNotes(pain.getPainManagementNotes());
        }

        // Delivery Room Environment
        if (request.getDeliveryRoomEnvironment() != null) {
            BirthPlanRequestDTO.DeliveryRoomEnvironmentDTO environment = request.getDeliveryRoomEnvironment();
            birthPlan.setSupportPersons(
                environment.getSupportPersons() != null ? new ArrayList<>(environment.getSupportPersons()) : new ArrayList<>()
            );
            birthPlan.setLightingPreference(environment.getLightingPreference());
            birthPlan.setMusicPreference(environment.getMusicPreference());
            birthPlan.setFetalMonitoringStyle(environment.getFetalMonitoringStyle());
            birthPlan.setComfortItems(
                environment.getComfortItems() != null ? new ArrayList<>(environment.getComfortItems()) : new ArrayList<>()
            );
            birthPlan.setMovementDuringLabor(environment.getMovementDuringLabor());
            birthPlan.setEnvironmentNotes(environment.getEnvironmentNotes());
        }

        // Postpartum Preferences
        if (request.getPostpartumPreferences() != null) {
            BirthPlanRequestDTO.PostpartumPreferencesDTO postpartum = request.getPostpartumPreferences();
            birthPlan.setDelayedCordClamping(postpartum.getDelayedCordClamping());
            birthPlan.setCordClampingDuration(postpartum.getCordClampingDuration());
            birthPlan.setWhoCutsCord(postpartum.getWhoCutsCord());
            birthPlan.setSkinToSkinContact(postpartum.getSkinToSkinContact());
            birthPlan.setVitaminKShot(postpartum.getVitaminKShot());
            birthPlan.setEyeOintment(postpartum.getEyeOintment());
            birthPlan.setHepatitisBVaccine(postpartum.getHepatitisBVaccine());
            birthPlan.setFirstBathTiming(postpartum.getFirstBathTiming());
            birthPlan.setFeedingMethod(postpartum.getFeedingMethod());
            birthPlan.setPostpartumNotes(postpartum.getPostpartumNotes());
        }

        // Additional & Acknowledgments
        birthPlan.setAdditionalWishes(request.getAdditionalWishes());
        birthPlan.setFlexibilityAcknowledgment(request.getFlexibilityAcknowledgment());
        birthPlan.setDiscussedWithProvider(request.getDiscussedWithProvider());
    }

    private BirthPlanResponseDTO.IntroductionDTO mapIntroductionToResponse(BirthPlan birthPlan) {
        return BirthPlanResponseDTO.IntroductionDTO.builder()
            .patientName(birthPlan.getPatientName())
            .expectedDueDate(birthPlan.getExpectedDueDate())
            .placeOfBirth(birthPlan.getPlaceOfBirth())
            .healthcareProvider(birthPlan.getHealthcareProvider())
            .medicalConditions(birthPlan.getMedicalConditions())
            .build();
    }

    private BirthPlanResponseDTO.DeliveryPreferencesDTO mapDeliveryPreferencesToResponse(BirthPlan birthPlan) {
        return BirthPlanResponseDTO.DeliveryPreferencesDTO.builder()
            .preferredDeliveryMethod(birthPlan.getPreferredDeliveryMethod())
            .backupDeliveryMethod(birthPlan.getBackupDeliveryMethod())
            .deliveryMethodNotes(birthPlan.getDeliveryMethodNotes())
            .build();
    }

    private BirthPlanResponseDTO.PainManagementDTO mapPainManagementToResponse(BirthPlan birthPlan) {
        return BirthPlanResponseDTO.PainManagementDTO.builder()
            .preferredApproach(birthPlan.getPreferredPainApproach())
            .unmedicatedTechniques(birthPlan.getUnmedicatedTechniques())
            .medicatedOptions(birthPlan.getMedicatedOptions())
            .painManagementNotes(birthPlan.getPainManagementNotes())
            .build();
    }

    private BirthPlanResponseDTO.DeliveryRoomEnvironmentDTO mapDeliveryRoomEnvironmentToResponse(BirthPlan birthPlan) {
        return BirthPlanResponseDTO.DeliveryRoomEnvironmentDTO.builder()
            .supportPersons(birthPlan.getSupportPersons())
            .lightingPreference(birthPlan.getLightingPreference())
            .musicPreference(birthPlan.getMusicPreference())
            .fetalMonitoringStyle(birthPlan.getFetalMonitoringStyle())
            .comfortItems(birthPlan.getComfortItems())
            .movementDuringLabor(birthPlan.getMovementDuringLabor())
            .environmentNotes(birthPlan.getEnvironmentNotes())
            .build();
    }

    private BirthPlanResponseDTO.PostpartumPreferencesDTO mapPostpartumPreferencesToResponse(BirthPlan birthPlan) {
        return BirthPlanResponseDTO.PostpartumPreferencesDTO.builder()
            .delayedCordClamping(birthPlan.getDelayedCordClamping())
            .cordClampingDuration(birthPlan.getCordClampingDuration())
            .whoCutsCord(birthPlan.getWhoCutsCord())
            .skinToSkinContact(birthPlan.getSkinToSkinContact())
            .vitaminKShot(birthPlan.getVitaminKShot())
            .eyeOintment(birthPlan.getEyeOintment())
            .hepatitisBVaccine(birthPlan.getHepatitisBVaccine())
            .firstBathTiming(birthPlan.getFirstBathTiming())
            .feedingMethod(birthPlan.getFeedingMethod())
            .postpartumNotes(birthPlan.getPostpartumNotes())
            .build();
    }
}
