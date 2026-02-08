package com.example.hms.payload.dto;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.DischargeDisposition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for admission details
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionResponseDTO {

    private UUID id;
    
    // Patient info
    private UUID patientId;
    private String patientName;
    private String patientMrn;

    // Hospital info
    private UUID hospitalId;
    private String hospitalName;

    // Provider info
    private UUID admittingProviderId;
    private String admittingProviderName;

    private UUID departmentId;
    private String departmentName;

    private String roomBed;

    private AdmissionType admissionType;
    private AdmissionStatus status;
    private AcuityLevel acuityLevel;

    private LocalDateTime admissionDateTime;
    private LocalDateTime expectedDischargeDateTime;
    private LocalDateTime actualDischargeDateTime;

    private String chiefComplaint;
    private String primaryDiagnosisCode;
    private String primaryDiagnosisDescription;
    private List<Map<String, String>> secondaryDiagnoses;

    private String admissionSource;

    // Order sets applied
    private List<AdmissionOrderSetResponseDTO> appliedOrderSets;

    private List<Map<String, Object>> customOrders;
    private String admissionNotes;

    // Attending physician
    private UUID attendingPhysicianId;
    private String attendingPhysicianName;

    private List<Map<String, String>> consultingPhysicians;

    // Discharge info
    private DischargeDisposition dischargeDisposition;
    private String dischargeSummary;
    private String dischargeInstructions;
    
    private UUID dischargingProviderId;
    private String dischargingProviderName;

    private List<Map<String, Object>> followUpAppointments;

    private String insuranceAuthNumber;
    private Integer lengthOfStayDays;

    private Map<String, Object> metadata;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
