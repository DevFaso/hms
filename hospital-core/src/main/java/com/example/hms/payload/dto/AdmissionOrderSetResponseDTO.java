package com.example.hms.payload.dto;

import com.example.hms.enums.AdmissionType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for admission order set details
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionOrderSetResponseDTO {

    private UUID id;
    private String name;
    private String description;
    private AdmissionType admissionType;
    
    private UUID departmentId;
    private String departmentName;
    
    private UUID hospitalId;
    private String hospitalName;
    
    private List<Map<String, Object>> orderItems;
    private String clinicalGuidelines;
    private Boolean active;
    private Integer version;
    
    private UUID createdById;
    private String createdByName;
    
    private UUID lastModifiedById;
    private String lastModifiedByName;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deactivatedAt;
    private String deactivationReason;
    
    /**
     * Number of orders in this set
     */
    private Integer orderCount;
}
