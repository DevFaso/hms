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

    @Size(max = 100)
    private String emergencyContactName;

    @Size(max = 20)
    private String emergencyContactPhone;

    @Size(max = 255)
    private String preferredPharmacy;

    @Size(max = 2000)
    private String careTeamNotes;

    private List<String> chronicConditions;
}
