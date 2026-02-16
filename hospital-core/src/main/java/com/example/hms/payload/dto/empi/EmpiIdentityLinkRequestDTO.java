package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class EmpiIdentityLinkRequestDTO {

    @NotNull(message = "Patient identifier is required")
    private UUID patientId;

    private UUID organizationId;
    private UUID hospitalId;
    private UUID departmentId;

    @Size(max = 100)
    private String sourceSystem;

    @Size(max = 100)
    private String mrnSnapshot;

    private String metadata;

    private EmpiAliasType aliasType;

    @Size(max = 255)
    private String aliasValue;

    @Size(max = 100)
    private String aliasSourceSystem;
}
