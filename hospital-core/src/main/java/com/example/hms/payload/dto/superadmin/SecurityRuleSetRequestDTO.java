package com.example.hms.payload.dto.superadmin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SecurityRuleSetRequestDTO {

    @NotBlank
    @Size(max = 160)
    private String name;

    @Size(max = 500)
    private String description;

    @NotBlank
    @Size(max = 60)
    private String enforcementScope;

    @Size(max = 120)
    private String publishedBy;

    @Valid
    private List<SecurityRuleDefinitionDTO> rules;
}
