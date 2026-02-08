package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for Birth Plan with all sections and provider review information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BirthPlanResponseDTO {

    private UUID id;

    private UUID patientId;

    private UUID hospitalId;

    private IntroductionDTO introduction;

    private DeliveryPreferencesDTO deliveryPreferences;

    private PainManagementDTO painManagement;

    private DeliveryRoomEnvironmentDTO deliveryRoomEnvironment;

    private PostpartumPreferencesDTO postpartumPreferences;

    private String additionalWishes;

    private Boolean flexibilityAcknowledgment;

    private Boolean discussedWithProvider;

    private Boolean providerReviewRequired;

    private Boolean providerReviewed;

    private String providerSignature;

    private LocalDateTime providerSignatureDate;

    private String providerComments;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntroductionDTO {
        private String patientName;
        private LocalDate expectedDueDate;
        private String placeOfBirth;
        private String healthcareProvider;
        private String medicalConditions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryPreferencesDTO {
        private String preferredDeliveryMethod;
        private String backupDeliveryMethod;
        private String deliveryMethodNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PainManagementDTO {
        private String preferredApproach;
        private List<String> unmedicatedTechniques;
        private List<String> medicatedOptions;
        private String painManagementNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryRoomEnvironmentDTO {
        private List<String> supportPersons;
        private String lightingPreference;
        private String musicPreference;
        private String fetalMonitoringStyle;
        private List<String> comfortItems;
        private Boolean movementDuringLabor;
        private String environmentNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostpartumPreferencesDTO {
        private Boolean delayedCordClamping;
        private Integer cordClampingDuration;
        private String whoCutsCord;
        private Boolean skinToSkinContact;
        private String vitaminKShot;
        private String eyeOintment;
        private String hepatitisBVaccine;
        private String firstBathTiming;
        private String feedingMethod;
        private String postpartumNotes;
    }
}
