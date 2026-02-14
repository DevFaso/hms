package com.example.hms.patient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class PatientRequest {
    @NotBlank
    @Size(max = 64)
    private String mrn;

    @NotBlank
    @Size(max = 120)
    private String firstName;

    @NotBlank
    @Size(max = 120)
    private String lastName;

    @Size(max = 120)
    private String middleName;

    @NotNull
    private LocalDate dateOfBirth;

    @NotBlank
    @Size(max = 32)
    private String gender;

    @Size(max = 40)
    private String phone;

    @Email
    @Size(max = 180)
    private String email;

    @Valid
    private AddressDto address;

    @Valid
    private MedicalHistoryDto medicalHistory;

    @Valid
    private List<PatientInsuranceDto> insurances = new ArrayList<>();

    /**
     * Custom setter to guard against null insurance list.
     */
    public void setInsurances(List<PatientInsuranceDto> insurances) {
        this.insurances = insurances == null ? new ArrayList<>() : insurances;
    }
}
