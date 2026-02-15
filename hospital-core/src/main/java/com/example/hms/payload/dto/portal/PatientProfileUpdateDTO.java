package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Fields a patient is allowed to update on their own profile.
 * Staff-managed fields (bloodType, allergies, etc.) are excluded.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientProfileUpdateDTO {

    // Contact info
    @Size(max = 100)
    private String phoneNumberPrimary;

    @Size(max = 100)
    private String phoneNumberSecondary;

    @Email @Size(max = 150)
    private String email;

    // Address
    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 100)
    private String zipCode;

    @Size(max = 100)
    private String country;

    // Emergency contact
    @Size(max = 100)
    private String emergencyContactName;

    @Size(max = 20)
    private String emergencyContactPhone;

    @Size(max = 50)
    private String emergencyContactRelationship;

    // Preferred pharmacy
    @Size(max = 255)
    private String preferredPharmacy;
}
