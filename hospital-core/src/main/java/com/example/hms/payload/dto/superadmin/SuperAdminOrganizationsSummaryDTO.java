package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminOrganizationsSummaryDTO {

    private List<DirectoryEntryDTO> directory;
    private List<ComplianceAlertDTO> complianceAlerts;
    private List<LocalizationDefaultsDTO> localizationDefaults;
    private ImplementationStatusDTO implementationStatus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirectoryEntryDTO {
        private UUID id;
        private String name;
        private String status;
        private String compliancePosture;
        private String primaryContactName;
        private String primaryContactEmail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceAlertDTO {
        private UUID id;
        private UUID organizationId;
        private String organizationName;
        private String complianceType;
        private String severity;
        private LocalDate expiresOn;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocalizationDefaultsDTO {
        private UUID id;
        private UUID organizationId;
        private String organizationName;
        private String fallbackLocale;
        private String contactLanguage;
        private String currency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImplementationStatusDTO {
        private boolean directoryReady;
        private boolean creationWizardReady;
        private boolean brandLocaleProfilesReady;
        private boolean documentationReady;
    }
}
