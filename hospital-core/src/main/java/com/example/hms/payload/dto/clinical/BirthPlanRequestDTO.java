package com.example.hms.payload.dto.clinical;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating or updating a birth plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BirthPlanRequestDTO {

    private UUID patientId;

    private UUID hospitalId;

    @NotNull(message = "Introduction section is required")
    private IntroductionDTO introduction;

    private DeliveryPreferencesDTO deliveryPreferences;

    private PainManagementDTO painManagement;

    private DeliveryRoomEnvironmentDTO deliveryRoomEnvironment;

    private PostpartumPreferencesDTO postpartumPreferences;

    private String additionalWishes;

    @NotNull(message = "Flexibility acknowledgment is required")
    private Boolean flexibilityAcknowledgment;

    private Boolean discussedWithProvider;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntroductionDTO {
        @NotBlank(message = "Patient name is required")
        @Size(max = 255)
        private String patientName;

        @NotNull(message = "Expected due date is required")
        private LocalDate expectedDueDate;

        @Size(max = 255)
        private String placeOfBirth;

        @Size(max = 255)
        private String healthcareProvider;

        private String medicalConditions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryPreferencesDTO {
        @Size(max = 50)
        private String preferredDeliveryMethod;

        @Size(max = 50)
        private String backupDeliveryMethod;

        private String deliveryMethodNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PainManagementDTO {
        @Size(max = 50)
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

        @Size(max = 100)
        private String lightingPreference;

        @Size(max = 255)
        private String musicPreference;

        @Size(max = 50)
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

        @Size(max = 100)
        private String whoCutsCord;

        private Boolean skinToSkinContact;

        @Size(max = 20)
        private String vitaminKShot;

        @Size(max = 20)
        private String eyeOintment;

        @Size(max = 20)
        private String hepatitisBVaccine;

        @Size(max = 50)
        private String firstBathTiming;

        @Size(max = 50)
        private String feedingMethod;

        private String postpartumNotes;
    }
}
