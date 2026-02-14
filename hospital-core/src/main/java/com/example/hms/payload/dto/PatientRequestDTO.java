package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientRequestDTO {
    // Optional: Insurance info for patient intake
    private PatientInsuranceRequestDTO insurance;

    private UUID id;

    @NotBlank(message = "First name is required.")
    private String firstName;

    @NotBlank(message = "Last name is required.")
    private String lastName;

    @Size(max = 50, message = "Middle name cannot exceed 50 characters.")
    private String middleName;

    @NotNull(message = "Date of birth is required.")
    private LocalDate dateOfBirth;

    @NotBlank(message = "Gender is required.")
    @Size(max = 10, message = "Gender cannot exceed 10 characters.")
    private String gender;

    @NotBlank(message = "Address is required.")
    @Size(max = 255, message = "Address cannot exceed 255 characters.")
    private String address;

    private String addressLine1;
    private String addressLine2;

    private String city;
    private String state;
    private String zipCode;
    private String country;

    @NotBlank(message = "Primary phone number is required.")
    @Size(max = 15, message = "Phone number cannot exceed 15 characters.")
    private String phoneNumberPrimary;

    private String phoneNumberSecondary;

    @Email(message = "Email should be valid.")
    @NotBlank(message = "Email is required.")
    private String email;

    // Guardian/emergency contact info - optional here, validated manually if under 18
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String emergencyContactRelationship;

    @Size(max = 5, message = "Blood type cannot exceed 5 characters.")
    private String bloodType;

    @Size(max = 255, message = "Allergies cannot exceed 255 characters.")
    private String allergies;

    @Size(max = 500, message = "Medical history summary cannot exceed 500 characters.")
    private String medicalHistorySummary;

    private String preferredPharmacy;
    private String careTeamNotes;
    private List<String> chronicConditions;

    @NotNull(message = "User ID is required.")
    private UUID userId;

    private UUID organizationId;
    private UUID hospitalId;
    private UUID departmentId;
    @Builder.Default
    private boolean isActive = true;

    public boolean isMinor() {
        return dateOfBirth != null && java.time.Period.between(dateOfBirth, LocalDate.now()).getYears() < 18;
    }
}
