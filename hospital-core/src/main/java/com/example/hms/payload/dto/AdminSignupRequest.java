package com.example.hms.payload.dto;

import com.example.hms.enums.JobTitle;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.Specialization; // <- only if you added this enum
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@LicenseRequiredForMedicalRoles(message = "License number is required for medical roles.")
public class AdminSignupRequest {
    /** Optional: hospital name for registration (alternative to hospitalId). */
    @JsonProperty("hospitalName")
    private String hospitalName;

    @NotBlank(message = "Username is required.")
    private String username;

    @Email
    @NotBlank(message = "Email is required.")
    private String email;

    // Password is optional for non-patient staff registrations: when omitted the service
    // auto-generates a temporary password and sets forcePasswordChange=true so the user
    // is prompted to choose a permanent one on first login.
    private String password;

    @NotBlank(message = "First name is required.")
    private String firstName;

    @NotBlank(message = "Last name is required.")
    private String lastName;

    @NotBlank(message = "Phone number is required.")
    private String phoneNumber;

    // IMPORTANT: keep nullable so receptionists aren't forced to send it
    @JsonProperty("hospitalId")
    private UUID hospitalId;

    @NotEmpty(message = "At least one role is required.")
    private Set<@NotBlank String> roleNames;

    private String licenseNumber;              // required only for medical/admin roles (validator)
    private JobTitle jobTitle;                 // optional
    private EmploymentType employmentType;     // optional; service defaults FULL_TIME if null
    private UUID departmentId;                 // optional
    private Specialization specialization;     // optional
    private Boolean forcePasswordChange;       // optional
}
