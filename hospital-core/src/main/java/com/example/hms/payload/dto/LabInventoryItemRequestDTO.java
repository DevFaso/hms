package com.example.hms.payload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabInventoryItemRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String itemCode;

    @Size(max = 100)
    private String category;

    @NotNull
    @Min(0)
    private Integer quantity;

    @Size(max = 50)
    private String unit;

    @NotNull
    @Min(0)
    private Integer reorderThreshold;

    @Size(max = 255)
    private String supplier;

    @Size(max = 100)
    private String lotNumber;

    private LocalDate expirationDate;

    @Size(max = 2048)
    private String notes;
}
