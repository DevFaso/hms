package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabInstrumentResponseDTO {

    private String id;
    private String name;
    private String manufacturer;
    private String modelNumber;
    private String serialNumber;
    private String hospitalId;
    private String hospitalName;
    private String departmentId;
    private String departmentName;
    private String status;
    private LocalDate installationDate;
    private LocalDate lastCalibrationDate;
    private LocalDate nextCalibrationDate;
    private LocalDate lastMaintenanceDate;
    private LocalDate nextMaintenanceDate;
    private boolean maintenanceOverdue;
    private boolean calibrationOverdue;
    private String notes;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
