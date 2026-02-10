package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminOrganizationHierarchyResponseDTO {

    @Builder.Default
    private List<OrganizationHierarchyDTO> organizations = List.of();
    private long totalOrganizations;
    private long totalHospitals;
    private long totalStaff;
    private long totalPatients;
    private boolean includeStaff;
    private boolean includePatients;
    private Boolean activeOnly;
    private String search;
    private int staffLimit;
    private int patientLimit;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrganizationHierarchyDTO {
        private UUID organizationId;
        private String organizationName;
        private String organizationCode;
        private String organizationType;
        private boolean active;
        @Builder.Default
        private List<HospitalHierarchyDTO> hospitals = List.of();
        private long totalHospitals;
        private long totalStaff;
        private long totalPatients;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HospitalHierarchyDTO {
        private UUID hospitalId;
        private String hospitalName;
        private String hospitalCode;
        private String city;
        private String state;
        private boolean active;
        private long staffCount;
        private long patientCount;
        @Builder.Default
        private List<StaffSummaryDTO> staff = List.of();
        @Builder.Default
        private List<PatientSummaryDTO> patients = List.of();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StaffSummaryDTO {
        private UUID id;
        private String fullName;
        private String role;
        private String jobTitle;
        private String email;
        private String phoneNumber;
        private boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PatientSummaryDTO {
        private UUID id;
        private String fullName;
        private String mrn;
        private String email;
        private String phoneNumber;
        private LocalDate dateOfBirth;
        private boolean active;
    }
}
