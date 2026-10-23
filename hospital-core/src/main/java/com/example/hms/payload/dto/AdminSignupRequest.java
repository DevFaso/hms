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
public class AdminSignupRequest {
    /** Optional: hospital name for registration (alternative to hospitalId). */
    @JsonProperty("hospitalName")
    private String hospitalName;

    // Optional: when omitted the service generates a temporary unique username
    // from the first/last name and sets forceUsernameChange=true.
    private String username;

    // Optional for PATIENT registrations (phone-first: most patients have no
    // email). Staff/admin roles still require it — enforced in UserServiceImpl
    // because the requirement depends on roleNames.
    @Email
    private String email;

    // Optional, kept for API compatibility: when omitted (the portal never sends
    // it) the service generates a strong one-time password and sets
    // forcePasswordChange=true. An admin-typed password is never e-mailed.
    private String password;

    @NotBlank(message = "{adminSignup.firstName.required}")
    private String firstName;

    @NotBlank(message = "{adminSignup.lastName.required}")
    private String lastName;

    @NotBlank(message = "{adminSignup.phoneNumber.required}")
    private String phoneNumber;

    // IMPORTANT: keep nullable so receptionists aren't forced to send it
    @JsonProperty("hospitalId")
    private UUID hospitalId;

    @NotEmpty(message = "{adminSignup.roleNames.required}")
    private Set<@NotBlank String> roleNames;

    private String licenseNumber;              // optional for every role: licences are never mandatory
    private JobTitle jobTitle;                 // optional
    private EmploymentType employmentType;     // optional; service defaults FULL_TIME if null
    private UUID departmentId;                 // optional
    private Specialization specialization;     // optional
    private Boolean forcePasswordChange;       // optional
    /**
     * QA D626: set by the patient form (step 1 of a patient registration).
     * A PATIENT role on admin-register without it is a staff onboarding that
     * picked the patient role, and is refused: a patient is registered from
     * the patient form, which also creates the patient record.
     */
    private Boolean patientRegistration;       // optional
}
