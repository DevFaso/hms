package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabInstrumentRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String manufacturer;

    @Size(max = 255)
    private String modelNumber;

    @NotBlank
    @Size(max = 100)
    private String serialNumber;

    private String departmentId;

    private String status;

    private LocalDate installationDate;

    private LocalDate lastCalibrationDate;

    private LocalDate nextCalibrationDate;

    private LocalDate lastMaintenanceDate;

    private LocalDate nextMaintenanceDate;

    @Size(max = 2048)
    private String notes;
}
