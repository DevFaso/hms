package com.example.hms.payload.dto.superadmin;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SecurityRuleTemplateImportResponseDTO {
    String templateCode;
    String templateTitle;
    Integer importedRuleCount;
    SecurityRuleSetResponseDTO ruleSet;
    List<SecurityRuleDefinitionDTO> importedRules;
    OffsetDateTime importedAt;
}
