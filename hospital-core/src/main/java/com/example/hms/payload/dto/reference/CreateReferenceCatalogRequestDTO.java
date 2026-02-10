package com.example.hms.payload.dto.reference;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateReferenceCatalogRequestDTO {

    @Schema(description = "Unique catalog code (e.g. department_types)")
    @NotBlank(message = "Catalog code is required")
    @Size(max = 120, message = "Catalog code must be 120 characters or less")
    private String code;

    @Schema(description = "Display name for the catalog")
    @NotBlank(message = "Catalog name is required")
    @Size(max = 255, message = "Catalog name must be 255 characters or less")
    private String name;

    @Schema(description = "Optional catalog description")
    @Size(max = 2000, message = "Description must be 2000 characters or less")
    private String description;
}
