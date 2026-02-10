package com.example.hms.patient.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "patients", indexes = {
    @Index(name = "idx_patients_mrn", columnList = "mrn"),
    @Index(name = "idx_patients_last_name", columnList = "last_name"),
    @Index(name = "idx_patients_phone", columnList = "phone")
})
public class Patient {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String mrn;

    @Column(name = "first_name", nullable = false, length = 120)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 120)
    private String lastName;

    @Column(name = "middle_name", length = 120)
    private String middleName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(nullable = false, length = 32)
    private String gender;

    @Column(length = 40)
    private String phone;

    @Column(length = 180)
    private String email;

    @Column(name = "address_line1", length = 180)
    private String addressLine1;

    @Column(name = "address_line2", length = 180)
    private String addressLine2;

    @Column(length = 120)
    private String city;

    @Column(length = 120)
    private String state;

    @Column(name = "postal_code", length = 40)
    private String postalCode;

    @Column(length = 120)
    private String country;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PatientInsurance> insurances = new ArrayList<>();

    @OneToOne(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private PatientMedicalHistory medicalHistory;

    public UUID getId() {
        return id;
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

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<PatientInsurance> getInsurances() {
        return insurances;
    }

    public void setInsurances(List<PatientInsurance> insurances) {
        this.insurances.clear();
        if (insurances != null) {
            insurances.forEach(this::addInsurance);
        }
    }

    public void addInsurance(PatientInsurance insurance) {
        if (insurance == null) {
            return;
        }
        insurance.setPatient(this);
        this.insurances.add(insurance);
    }

    public PatientMedicalHistory getMedicalHistory() {
        return medicalHistory;
    }

    public void setMedicalHistory(PatientMedicalHistory medicalHistory) {
        if (medicalHistory != null) {
            medicalHistory.setPatient(this);
        }
        this.medicalHistory = medicalHistory;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Patient)) {
            return false;
        }
        Patient patient = (Patient) o;
        return Objects.equals(id, patient.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
