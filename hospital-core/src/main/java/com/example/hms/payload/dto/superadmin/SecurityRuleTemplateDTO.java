package com.example.hms.payload.dto.superadmin;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SecurityRuleTemplateDTO {
    String code;
    String title;
    String category;
    String summary;
    List<String> controllers;
    List<SecurityRuleDefinitionDTO> defaultRules;
}
