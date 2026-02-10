package com.example.hms.payload.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientResponseDTO {
    public static final UUID ZERO = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private UUID id;
    private String firstName;
    private String lastName;
    private String middleName;
    private LocalDate dateOfBirth;
    private String gender;
    private String address;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String zipCode;
    private String postalCode;
    private String country;
    private String phoneNumberPrimary;
    private String phoneNumberSecondary;
    private String email;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String emergencyContactRelationship;
    private String bloodType;
    private String allergies;
    private String medicalHistorySummary;
    private String preferredPharmacy;
    private String careTeamNotes;
    private String mrn;

    private String displayName;
    private String username;
    private String room;
    private String bed;
    private Integer hr;
    private String bp;
    private Integer spo2;

    @Builder.Default
    private List<String> flags = new ArrayList<>();

    @Builder.Default
    private List<String> risks = new ArrayList<>();

    @Builder.Default
    private List<String> chronicConditions = new ArrayList<>();

    private VitalSnapshot lastVitals;

    @Builder.Default
    private UUID organizationId        = ZERO;

    // ✅ Field initializers (work with MapStruct/Jackson)
    @Builder.Default
    private UUID   hospitalId            = ZERO;
    @Builder.Default
    private String hospitalName          = "";
    @Builder.Default
    private UUID   departmentId          = ZERO;
    private String departmentName;

    @Builder.Default
    private UUID   patientId             = ZERO;
    @Builder.Default
    private String patientName           = "";
    @Builder.Default
    private String patientEmail          = "";
    @Builder.Default
    private String patientPhoneNumber    = "";

    @Builder.Default
    private UUID   primaryHospitalId     = ZERO;
    @Builder.Default
    private String primaryHospitalName   = "";
    @Builder.Default
    private String primaryHospitalAddress= "";
    @Builder.Default
    private String primaryHospitalCode   = "";
    @Builder.Default
    private LocalDate registrationDate   = LocalDate.of(1970, 1, 1);

    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VitalSnapshot {
        private Integer heartRate;
        private String bloodPressure;
        private Integer systolicBp;
        private Integer diastolicBp;
        private Integer respiratoryRate;
        private Integer spo2;
        private Integer bloodGlucose;
        private Double temperature;
        private Double weightKg;
        private String bodyPosition;
        private String notes;
        private Boolean clinicallySignificant;
        private LocalDateTime recordedAt;
    }
}
