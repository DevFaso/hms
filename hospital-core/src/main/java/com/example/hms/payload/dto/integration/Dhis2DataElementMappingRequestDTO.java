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
        message = "DHIS2 dataElement UID must be 11 chars (letter + 10 alphanumeric)")
    String dhis2DataElementUid,

    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 categoryOptionCombo UID must be 11 chars when supplied")
    String dhis2CategoryOptionComboUid,

    @NotNull Dhis2PeriodType periodType,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataset UID must be 11 chars (letter + 10 alphanumeric)")
    String datasetUid,

    Boolean active
) { }
