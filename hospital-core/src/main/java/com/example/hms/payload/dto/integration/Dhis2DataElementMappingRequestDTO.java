package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Dhis2DataElementMappingRequestDTO(

    @NotBlank @Size(max = 255) String hmsConceptSystem,

    @NotBlank @Size(max = 64) String hmsConceptCode,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "{dhis2Mapping.dataElementUid.pattern}")
    String dhis2DataElementUid,

    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "{dhis2Mapping.categoryOptionComboUid.pattern}")
    String dhis2CategoryOptionComboUid,

    @NotNull Dhis2PeriodType periodType,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "{dhis2Mapping.datasetUid.pattern}")
    String datasetUid,

    Boolean active
) { }
