package com.example.hms.payload.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient's view of their own profile — no clinical staff fields,
 * no internal IDs they shouldn't see.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientProfileDTO {

    private UUID id;

    // Demographics
    private String firstName;
    private String lastName;
    private String middleName;
    private LocalDate dateOfBirth;
    private String gender;

    // Contact
    private String phoneNumberPrimary;
    private String phoneNumberSecondary;
    private String email;

    // Address
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String zipCode;
    private String country;

    // Emergency contact
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String emergencyContactRelationship;

    // Medical basics (read-only for patient)
    private String bloodType;
    private String allergies;
    private String chronicConditions;
    private String preferredPharmacy;

    // Display
    private String mrn;
    private String username;
}
