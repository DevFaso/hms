package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Social history details")
public class SocialHistoryResponseDTO {

    @Schema(description = "Unique identifier")
    private UUID id;

    @Schema(description = "Patient ID")
    private UUID patientId;

    @Schema(description = "Patient name")
    private String patientName;

    @Schema(description = "Hospital ID")
    private UUID hospitalId;

    @Schema(description = "Hospital name")
    private String hospitalName;

    @Schema(description = "Staff member who recorded")
    private UUID recordedByStaffId;

    @Schema(description = "Recorder name")
    private String recordedByName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Date recorded")
    private LocalDate recordedDate;

    // Tobacco Use
    @Schema(description = "Uses tobacco")
    private Boolean tobaccoUse;

    @Schema(description = "Tobacco type")
    private String tobaccoType;

    @Schema(description = "Packs per day")
    private Double tobaccoPacksPerDay;

    @Schema(description = "Years used")
    private Integer tobaccoYearsUsed;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Quit date")
    private LocalDate tobaccoQuitDate;

    @Schema(description = "Tobacco notes")
    private String tobaccoNotes;

    // Alcohol Use
    @Schema(description = "Uses alcohol")
    private Boolean alcoholUse;

    @Schema(description = "Alcohol frequency")
    private String alcoholFrequency;

    @Schema(description = "Drinks per week")
    private Integer alcoholDrinksPerWeek;

    @Schema(description = "Binge drinking")
    private Boolean alcoholBingeDrinking;

    @Schema(description = "Alcohol notes")
    private String alcoholNotes;

    // Substance Use
    @Schema(description = "Recreational drug use")
    private Boolean recreationalDrugUse;

    @Schema(description = "Drug types")
    private String drugTypesUsed;

    @Schema(description = "IV drug use")
    private Boolean intravenousDrugUse;

    @Schema(description = "Substance abuse treatment")
    private Boolean substanceAbuseTreatment;

    @Schema(description = "Substance notes")
    private String substanceNotes;

    // Exercise
    @Schema(description = "Exercise frequency")
    private String exerciseFrequency;

    @Schema(description = "Exercise type")
    private String exerciseType;

    @Schema(description = "Exercise minutes per week")
    private Integer exerciseMinutesPerWeek;

    // Diet
    @Schema(description = "Diet type")
    private String dietType;

    @Schema(description = "Diet restrictions")
    private String dietRestrictions;

    @Schema(description = "Nutritional concerns")
    private String nutritionalConcerns;

    // Occupation
    @Schema(description = "Occupation")
    private String occupation;

    @Schema(description = "Employment status")
    private String employmentStatus;

    @Schema(description = "Occupational hazards")
    private String occupationalHazards;

    // Living Situation
    @Schema(description = "Marital status")
    private String maritalStatus;

    @Schema(description = "Living arrangement")
    private String livingArrangement;

    @Schema(description = "Housing stability")
    private Boolean housingStability;

    @Schema(description = "Household members")
    private Integer householdMembers;

    // Social Support
    @Schema(description = "Has primary caregiver")
    private Boolean hasPrimaryCaregiver;

    @Schema(description = "Social support network")
    private String socialSupportNetwork;

    @Schema(description = "Social isolation risk")
    private Boolean socialIsolationRisk;

    // Education
    @Schema(description = "Education level")
    private String educationLevel;

    @Schema(description = "Health literacy concerns")
    private Boolean healthLiteracyConcerns;

    @Schema(description = "Preferred language")
    private String preferredLanguage;

    @Schema(description = "Interpreter needed")
    private Boolean interpreterNeeded;

    // Financial
    @Schema(description = "Insurance status")
    private String insuranceStatus;

    @Schema(description = "Financial barriers")
    private Boolean financialBarriers;

    @Schema(description = "Transportation access")
    private Boolean transportationAccess;

    // Sexual History
    @Schema(description = "Sexually active")
    private Boolean sexuallyActive;

    @Schema(description = "Number of partners")
    private Integer numberOfPartners;

    @Schema(description = "Contraception use")
    private String contraceptionUse;

    @Schema(description = "STI history")
    private Boolean stiHistory;

    @Schema(description = "Sexual health notes")
    private String sexualHealthNotes;

    // Mental Health
    @Schema(description = "Stress level")
    private String stressLevel;

    @Schema(description = "Stress sources")
    private String stressSources;

    @Schema(description = "Coping mechanisms")
    private String copingMechanisms;

    @Schema(description = "Mental health support")
    private Boolean mentalHealthSupport;

    // Safety
    @Schema(description = "Domestic violence screening")
    private Boolean domesticViolenceScreening;

    @Schema(description = "Feels safe at home")
    private Boolean feelsSafeAtHome;

    @Schema(description = "Abuse history")
    private Boolean abuseHistory;

    @Schema(description = "Safety concerns")
    private String safetyConcerns;

    // Additional
    @Schema(description = "Additional notes")
    private String additionalNotes;

    @Schema(description = "Version number")
    private Integer versionNumber;

    @Schema(description = "Active")
    private Boolean active;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Created timestamp")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Updated timestamp")
    private LocalDateTime updatedAt;
}
