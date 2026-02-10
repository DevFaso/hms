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
    private String emergencyContactPhone;

    @NotBlank
    private String emergencyContactRelationship;

    private String bloodType;
    private String allergies;
    private String medicalHistorySummary;
}
