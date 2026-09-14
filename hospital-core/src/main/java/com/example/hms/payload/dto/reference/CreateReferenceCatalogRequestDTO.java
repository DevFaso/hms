package com.example.hms.payload.dto.reference;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateReferenceCatalogRequestDTO {

    @Schema(description = "Unique catalog code (e.g. department_types)")
    @NotBlank(message = "{referenceCatalog.code.required}")
    @Size(max = 120, message = "{referenceCatalog.code.size}")
    private String code;

    @Schema(description = "Display name for the catalog")
    @NotBlank(message = "{referenceCatalog.name.required}")
    @Size(max = 255, message = "{referenceCatalog.name.size}")
    private String name;

    @Schema(description = "Optional catalog description")
    @Size(max = 2000, message = "{referenceCatalog.description.size}")
    private String description;
}
