package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationCatalogItemResponseDTO {

    private UUID id;
    private String nameFr;
    private String genericName;
    private String brandName;
    private String atcCode;
    private String form;
    private String strength;
    private String strengthUnit;
    private String rxnormCode;
    private String route;
    private String category;
    private boolean essentialList;
    private boolean controlled;
    private boolean active;
    private String description;
    private UUID hospitalId;
    private String hospitalName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
