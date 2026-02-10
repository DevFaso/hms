package com.example.hms.payload.dto.superadmin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SecurityRuleSimulationRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String scenario;

    @Size(max = 200)
    private String audience;

    @Valid
    private List<SecurityRuleDefinitionDTO> rules;
}
