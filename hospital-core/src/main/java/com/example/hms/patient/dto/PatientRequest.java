package com.example.hms.patient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public AddressDto getAddress() {
        return address;
    }

    public void setAddress(AddressDto address) {
        this.address = address;
    }

    public MedicalHistoryDto getMedicalHistory() {
        return medicalHistory;
    }

    public void setMedicalHistory(MedicalHistoryDto medicalHistory) {
        this.medicalHistory = medicalHistory;
    }

    public List<PatientInsuranceDto> getInsurances() {
        return insurances;
    }

    public void setInsurances(List<PatientInsuranceDto> insurances) {
        this.insurances = insurances == null ? new ArrayList<>() : insurances;
    }
}
