package com.example.hms.payload.dto;

import com.example.hms.enums.AcuityLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for updating an existing admission
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionUpdateRequestDTO {

    private UUID departmentId;
    @jakarta.validation.constraints.Size(max = 50, message = "{admission.roomBed.size}")
    private String roomBed;
    private AcuityLevel acuityLevel;
    private LocalDateTime expectedDischargeDateTime;
    @jakarta.validation.constraints.Size(max = 2000, message = "{admission.admissionNotes.size}")
    private String admissionNotes;
    private UUID attendingPhysicianId;
    
    /**
     * Add consulting physicians
     */
    private List<Map<String, String>> consultingPhysiciansToAdd;
    
    /**
     * Add secondary diagnoses
     */
    private List<Map<String, String>> secondaryDiagnosesToAdd;
    
    private Map<String, Object> metadata;
}
