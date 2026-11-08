package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest extends BaseUserDTO {
    @NotBlank
    @Size(min = 8, max = 40)
    private String password;

    @NotBlank
    private String gender;

    @NotBlank
    private String address;

    private String city;
    private String state;
    private String zipCode;
    private String country;

    @NotBlank
    private String emergencyContactName;

    @NotBlank
    @com.example.hms.validation.PhoneNumber
    private String emergencyContactPhone;

    @NotBlank
    private String emergencyContactRelationship;

    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.AboGroup.BLOOD_TYPE_CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE, message = "{patient.bloodType.invalid}")
    private String bloodType;
    private String allergies;
    private String medicalHistorySummary;
}
