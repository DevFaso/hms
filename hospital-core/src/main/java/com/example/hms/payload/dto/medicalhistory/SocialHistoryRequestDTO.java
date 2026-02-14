package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create or update social history")
public class SocialHistoryRequestDTO {

    @NotNull(message = "Patient ID is required")
    @Schema(description = "Patient ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    @Schema(description = "Hospital ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @Schema(description = "Staff member recording this history")
    private UUID recordedByStaffId;

    @NotNull(message = "Recorded date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Date recorded", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate recordedDate;

    // Tobacco Use
    @Schema(description = "Does patient use tobacco products")
    private Boolean tobaccoUse;

    @Size(max = 100)
    @Schema(description = "Type of tobacco (cigarettes, cigars, vaping, etc.)")
    private String tobaccoType;

    @Schema(description = "Packs per day (for cigarette smokers)")
    private Double tobaccoPacksPerDay;

    @Schema(description = "Years of tobacco use")
    private Integer tobaccoYearsUsed;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Date patient quit tobacco (if applicable)")
    private LocalDate tobaccoQuitDate;

    @Size(max = 1000)
    @Schema(description = "Additional tobacco use notes")
    private String tobaccoNotes;

    // Alcohol Use
    @Schema(description = "Does patient consume alcohol")
    private Boolean alcoholUse;

    @Size(max = 100)
    @Schema(description = "Frequency of alcohol consumption")
    private String alcoholFrequency;

    @Schema(description = "Number of drinks per week")
    private Integer alcoholDrinksPerWeek;

    @Schema(description = "History of binge drinking")
    private Boolean alcoholBingeDrinking;

    @Size(max = 1000)
    @Schema(description = "Additional alcohol use notes")
    private String alcoholNotes;

    // Substance Use
    @Schema(description = "History of recreational drug use")
    private Boolean recreationalDrugUse;

    @Size(max = 500)
    @Schema(description = "Types of drugs used")
    private String drugTypesUsed;

    @Schema(description = "History of intravenous drug use")
    private Boolean intravenousDrugUse;

    @Schema(description = "Currently or previously in substance abuse treatment")
    private Boolean substanceAbuseTreatment;

    @Size(max = 1000)
    @Schema(description = "Additional substance use notes")
    private String substanceNotes;

    // Exercise & Physical Activity
    @Size(max = 100)
    @Schema(description = "Exercise frequency")
    private String exerciseFrequency;

    @Size(max = 255)
    @Schema(description = "Types of exercise")
    private String exerciseType;

    @Schema(description = "Minutes of exercise per week")
    private Integer exerciseMinutesPerWeek;

    // Diet & Nutrition
    @Size(max = 100)
    @Schema(description = "Type of diet")
    private String dietType;

    @Size(max = 500)
    @Schema(description = "Dietary restrictions")
    private String dietRestrictions;

    @Size(max = 1000)
    @Schema(description = "Nutritional concerns")
    private String nutritionalConcerns;

    // Occupation & Employment
    @Size(max = 200)
    @Schema(description = "Current or most recent occupation")
    private String occupation;

    @Size(max = 50)
    @Schema(description = "Employment status")
    private String employmentStatus;

    @Size(max = 1000)
    @Schema(description = "Occupational hazards or exposures")
    private String occupationalHazards;

    // Living Situation
    @Size(max = 50)
    @Schema(description = "Marital status")
    private String maritalStatus;

    @Size(max = 100)
    @Schema(description = "Living arrangement")
    private String livingArrangement;

    @Schema(description = "Stable housing vs at risk")
    private Boolean housingStability;

    @Schema(description = "Number of people in household")
    private Integer householdMembers;

    // Social Support
    @Schema(description = "Has primary caregiver")
    private Boolean hasPrimaryCaregiver;

    @Size(max = 500)
    @Schema(description = "Social support network")
    private String socialSupportNetwork;

    @Schema(description = "At risk for social isolation")
    private Boolean socialIsolationRisk;

    // Education & Literacy
    @Size(max = 100)
    @Schema(description = "Highest education level")
    private String educationLevel;

    @Schema(description = "Health literacy concerns")
    private Boolean healthLiteracyConcerns;

    @Size(max = 50)
    @Schema(description = "Preferred language")
    private String preferredLanguage;

    @Schema(description = "Interpreter needed for medical care")
    private Boolean interpreterNeeded;

    // Financial & Access
    @Size(max = 100)
    @Schema(description = "Insurance status")
    private String insuranceStatus;

    @Schema(description = "Financial barriers to care")
    private Boolean financialBarriers;

    @Schema(description = "Access to reliable transportation")
    private Boolean transportationAccess;

    // Sexual History
    @Schema(description = "Sexually active")
    private Boolean sexuallyActive;

    @Schema(description = "Number of sexual partners")
    private Integer numberOfPartners;

    @Size(max = 255)
    @Schema(description = "Contraception methods used")
    private String contraceptionUse;

    @Schema(description = "History of sexually transmitted infections")
    private Boolean stiHistory;

    @Size(max = 1000)
    @Schema(description = "Sexual health notes")
    private String sexualHealthNotes;

    // Mental Health & Stress
    @Size(max = 50)
    @Schema(description = "Current stress level")
    private String stressLevel;

    @Size(max = 1000)
    @Schema(description = "Sources of stress")
    private String stressSources;

    @Size(max = 1000)
    @Schema(description = "Coping mechanisms")
    private String copingMechanisms;

    @Schema(description = "Currently receiving mental health support")
    private Boolean mentalHealthSupport;

    // Safety & Abuse
    @Schema(description = "Domestic violence screening completed")
    private Boolean domesticViolenceScreening;

    @Schema(description = "Feels safe at home")
    private Boolean feelsSafeAtHome;

    @Schema(description = "History of abuse")
    private Boolean abuseHistory;

    @Size(max = 1000)
    @Schema(description = "Safety concerns")
    private String safetyConcerns;

    // Additional
    @Size(max = 2048)
    @Schema(description = "Additional notes")
    private String additionalNotes;

    @Schema(description = "Version number for tracking updates")
    private Integer versionNumber;

    @Schema(description = "Active record")
    private Boolean active;
}
