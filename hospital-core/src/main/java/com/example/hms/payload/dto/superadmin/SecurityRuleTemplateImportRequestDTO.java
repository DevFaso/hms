package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

@Data
public class SecurityRuleTemplateImportRequestDTO {

    @NotBlank
    @Size(max = 80)
    private String templateCode;

    private UUID targetRuleSetId;

    @Size(max = 120)
    private String requestedBy;
}
