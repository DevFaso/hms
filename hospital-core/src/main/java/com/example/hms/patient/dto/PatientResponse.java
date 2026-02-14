package com.example.hms.patient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
public class PatientResponse {
    private UUID id;
    private String mrn;
    private String firstName;
    private String lastName;
    private String middleName;
    private LocalDate dateOfBirth;
    private String gender;
    private String phone;
    private String email;
    private AddressDto address;
    private MedicalHistoryDto medicalHistory;
    private List<PatientInsuranceDto> insurances = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Custom setter to guard against null insurance list.
     */
    public void setInsurances(List<PatientInsuranceDto> insurances) {
        this.insurances = insurances == null ? new ArrayList<>() : insurances;
    }
}
