package com.example.hms.payload.dto.medication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationCatalogItemRequestDTO {

    @NotBlank(message = "Medication name (French) is required")
    @Size(max = 255)
    private String nameFr;

    @Size(max = 255)
    @NotBlank(message = "Generic name is required")
    private String genericName;

    @Size(max = 255)
    private String brandName;

    @Size(max = 20)
    private String atcCode;

    @Size(max = 100)
    private String form;

    @Size(max = 100)
    private String strength;

    @Size(max = 50)
    private String strengthUnit;

    @Size(max = 20)
    private String rxnormCode;

    @Size(max = 100)
    private String route;

    @Size(max = 100)
    private String category;

    private boolean essentialList;

    private boolean controlled;

    @Builder.Default
    private boolean active = true;

    @Size(max = 1000)
    private String description;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;
}
