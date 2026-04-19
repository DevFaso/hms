package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationCatalogItemRequestDTO {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotBlank(message = "Code is required")
    @Size(max = 30)
    private String code;

    @NotBlank(message = "Medication name (French) is required")
    @Size(max = 255)
    private String nameFr;

    @Size(max = 255)
    private String genericName;

    @Size(max = 10)
    private String atcCode;

    @Size(max = 80)
    private String form;

    @Size(max = 100)
    private String strength;

    @Size(max = 60)
    private String unit;

    @Size(max = 20)
    private String rxnormCode;

    @Size(max = 1000)
    private String description;

    private Boolean active;
}
