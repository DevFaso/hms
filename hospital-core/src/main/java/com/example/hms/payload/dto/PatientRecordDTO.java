package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Patient record data used for sharing and exporting purposes.")
public class PatientRecordDTO {

    @Schema(description = "Unique ID of the patient.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID patientId;

    @Schema(description = "Patient's first name.", example = "John")
    private String firstName;

    @Schema(description = "Patient's last name.", example = "Doe")
    private String lastName;

    @Schema(description = "Patient's middle name, if available.", example = "Michael")
    private String middleName;

    @Schema(description = "Patient's date of birth.", example = "1990-05-20")
    private LocalDate dateOfBirth;

    @Schema(description = "Patient's gender.", example = "Male")
    private String gender;

    @Schema(description = "Patient's blood type.", example = "O+")
    private String bloodType;

    @Schema(description = "Summary of patient's medical history.")
    private String medicalHistorySummary;

    @Schema(description = "Known patient allergies.", example = "Peanuts, Penicillin")
    private String allergies;

    @Schema(description = "Patient's primary residential address.", example = "123 Main St")
    private String address;

    @Schema(description = "Patient's city of residence.", example = "Seattle")
    private String city;

    @Schema(description = "Patient's state or province.", example = "WA")
    private String state;

    @Schema(description = "Patient's postal or ZIP code.", example = "98109")
    private String zipCode;

    @Schema(description = "Patient's country of residence.", example = "United States")
    private String country;

    @Schema(description = "Primary phone number of the patient.", example = "+1-555-123-4567")
    private String phoneNumberPrimary;

    @Schema(description = "Secondary phone number of the patient.", example = "+1-555-987-6543")
    private String phoneNumberSecondary;

    @Schema(description = "Primary email address of the patient.", example = "john.doe@example.com")
    private String email;

    @Schema(description = "Name of the patient's emergency contact.", example = "Jane Doe")
    private String emergencyContactName;

    @Schema(description = "Phone number of the patient's emergency contact.", example = "+1-555-222-1111")
    private String emergencyContactPhone;

    @Schema(description = "Relationship of the emergency contact to the patient.", example = "Spouse")
    private String emergencyContactRelationship;

    @Schema(description = "Set of MRIs assigned to the patient across hospitals.")
    private Set<String> hospitalMRNs;

    @Schema(description = "Mapping of hospital IDs to MRNs where the patient is registered.")
    private Map<UUID, String> hospitalMrnMap;

    @Schema(description = "Identifier of the consent authorizing this record share.")
    private UUID consentId;

    @Schema(description = "Timestamp when consent was granted.")
    private LocalDateTime consentTimestamp;

    @Schema(description = "Optional timestamp when the consent expires.")
    private LocalDateTime consentExpiration;

    @Schema(description = "Purpose recorded for the consent.")
    private String consentPurpose;

    @Schema(description = "Source hospital identifier for the share.")
    private UUID fromHospitalId;

    @Schema(description = "Source hospital readable name.")
    private String fromHospitalName;

    @Schema(description = "Target hospital identifier for the share.")
    private UUID toHospitalId;

    @Schema(description = "Target hospital readable name.")
    private String toHospitalName;

    @Builder.Default
    @Schema(description = "Encounter history scoped to the source hospital.")
    private List<EncounterResponseDTO> encounters = emptyList();

    @Builder.Default
    @Schema(description = "Structured allergy entries documented for the patient.")
    private List<PatientAllergyResponseDTO> allergiesDetailed = emptyList();

    @Builder.Default
    @Schema(description = "Treatments captured during the encounters in scope.")
    private List<EncounterTreatmentResponseDTO> treatments = emptyList();

    @Builder.Default
    @Schema(description = "Lab orders attached to the encounters in scope.")
    private List<LabOrderResponseDTO> labOrders = emptyList();

    @Builder.Default
    @Schema(description = "Lab results attached to the encounters in scope.")
    private List<LabResultResponseDTO> labResults = emptyList();

    @Builder.Default
    @Schema(description = "Active and historical prescriptions written at the source hospital.")
    private List<PrescriptionResponseDTO> prescriptions = emptyList();

    @Builder.Default
    @Schema(description = "Insurance coverages relevant to the source hospital context.")
    private List<PatientInsuranceResponseDTO> insurances = emptyList();

    @Builder.Default
    @Schema(description = "Historical encounter change log scoped to the encounters shared.")
    private List<EncounterHistoryResponseDTO> encounterHistory = emptyList();

    @Builder.Default
    @Schema(description = "Active and historical clinical problems recorded for the patient.")
    private List<PatientProblemResponseDTO> problems = emptyList();

    @Builder.Default
    @Schema(description = "Documented surgical procedures relevant to the patient history.")
    private List<PatientSurgicalHistoryResponseDTO> surgicalHistory = emptyList();

    @Builder.Default
    @Schema(description = "Advance directives supplied with the consented record share.")
    private List<AdvanceDirectiveResponseDTO> advanceDirectives = emptyList();

    public Set<String> getHospitalMRNs() {
        return hospitalMRNs;
    }

    public Map<UUID, String> getHospitalMrnMap() {
        return hospitalMrnMap == null ? emptyMap() : hospitalMrnMap;
    }

    public List<EncounterResponseDTO> getEncounters() {
        return encounters == null ? emptyList() : encounters;
    }

    public List<PatientAllergyResponseDTO> getAllergiesDetailed() {
        return allergiesDetailed == null ? emptyList() : allergiesDetailed;
    }

    public List<EncounterTreatmentResponseDTO> getTreatments() {
        return treatments == null ? emptyList() : treatments;
    }

    public List<LabOrderResponseDTO> getLabOrders() {
        return labOrders == null ? emptyList() : labOrders;
    }

    public List<LabResultResponseDTO> getLabResults() {
        return labResults == null ? emptyList() : labResults;
    }

    public List<PrescriptionResponseDTO> getPrescriptions() {
        return prescriptions == null ? emptyList() : prescriptions;
    }

    public List<PatientInsuranceResponseDTO> getInsurances() {
        return insurances == null ? emptyList() : insurances;
    }

    public List<EncounterHistoryResponseDTO> getEncounterHistory() {
        return encounterHistory == null ? emptyList() : encounterHistory;
    }

    public List<PatientProblemResponseDTO> getProblems() {
        return problems == null ? emptyList() : problems;
    }

    public List<PatientSurgicalHistoryResponseDTO> getSurgicalHistory() {
        return surgicalHistory == null ? emptyList() : surgicalHistory;
    }

    public List<AdvanceDirectiveResponseDTO> getAdvanceDirectives() {
        return advanceDirectives == null ? emptyList() : advanceDirectives;
    }
}
