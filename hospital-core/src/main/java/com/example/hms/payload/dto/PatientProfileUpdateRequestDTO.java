package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientProfileUpdateRequestDTO {

    // QA D926: the front desk corrects the identity a patient was registered
    // with. Each field is optional (null = unchanged); a required one sent
    // blank is refused, never cleared.

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String middleName;

    @Size(max = 100)
    private String lastName;

    /** Same rule as the entity ({@code @Past}), so a refusal is a field 400, never a 500 at flush. */
    @jakarta.validation.constraints.Past
    private java.time.LocalDate dateOfBirth;

    @Size(max = 10)
    private String gender;

    @Size(max = 100)
    private String phoneNumberPrimary;

    @Size(max = 100)
    private String phoneNumberSecondary;

    @Email
    @Size(max = 150)
    private String email;

    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String postalCode;

    @Size(max = 100)
    private String country;

    /** Self-reported free text — optional (Tier 2 item 38). */
    @Size(max = 100, message = "{patientProfile.ethnicity.size}")
    private String ethnicity;

    @Size(max = 100)
    private String emergencyContactName;

    @Size(max = 20)
    private String emergencyContactPhone;

    /** QA D926: who the emergency contact or guardian is to the patient ("Mère"). */
    @Size(max = 50)
    private String emergencyContactRelationship;

    @Size(max = 255)
    private String preferredPharmacy;

    @Size(max = 2000)
    private String careTeamNotes;

    private List<String> chronicConditions;
}
