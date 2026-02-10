package com.example.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
