package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabInventoryItemResponseDTO {

    private String id;
    private String name;
    private String itemCode;
    private String category;
    private String hospitalId;
    private String hospitalName;
    private int quantity;
    private String unit;
    private int reorderThreshold;
    private boolean lowStock;
    private String supplier;
    private String lotNumber;
    private LocalDate expirationDate;
    private boolean expired;
    private String notes;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
