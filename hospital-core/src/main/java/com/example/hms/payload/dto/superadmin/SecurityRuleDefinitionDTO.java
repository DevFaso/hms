package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.SecurityRuleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class SecurityRuleDefinitionDTO {

    @NotBlank
    @Size(max = 160)
    private String name;

    @NotBlank
    @Size(max = 80)
    private String code;

    @Size(max = 1000)
    private String description;

    @NotNull
    private SecurityRuleType ruleType;

    @Size(max = 2000)
    private String ruleValue;

    private Integer priority;

    private List<@NotBlank @Size(max = 100) String> controllers;
}
