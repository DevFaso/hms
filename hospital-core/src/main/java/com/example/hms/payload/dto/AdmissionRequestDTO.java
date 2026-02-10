package com.example.hms.payload.dto;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating a new admission
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotNull(message = "Admitting provider ID is required")
    private UUID admittingProviderId;

    private UUID departmentId;

    private String roomBed;

    @NotNull(message = "Admission type is required")
    private AdmissionType admissionType;

    @NotNull(message = "Acuity level is required")
    private AcuityLevel acuityLevel;

    @NotNull(message = "Admission date/time is required")
    private LocalDateTime admissionDateTime;

    private LocalDateTime expectedDischargeDateTime;

    @NotBlank(message = "Chief complaint is required")
    private String chiefComplaint;

    private String primaryDiagnosisCode;

    private String primaryDiagnosisDescription;

    private List<Map<String, String>> secondaryDiagnoses;

    private String admissionSource;

    /**
     * IDs of order sets to apply during admission
     */
    private List<UUID> orderSetIds;

    /**
     * Custom orders not part of order sets
     */
    private List<Map<String, Object>> customOrders;

    private String admissionNotes;

    private UUID attendingPhysicianId;

    private String insuranceAuthNumber;

    private Map<String, Object> metadata;
}
