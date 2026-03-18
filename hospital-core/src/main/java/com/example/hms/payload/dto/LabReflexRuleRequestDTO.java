package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabReflexRuleRequestDTO {

    /** Test definition whose result triggers this rule. */
    private UUID triggerTestDefinitionId;

    /**
     * JSON condition string. Examples:
     * {"severityFlag":"ABNORMAL"}
     * {"thresholdOperator":"GT","thresholdValue":11.0}
     */
    private String condition;

    /** Test definition to auto-order when condition fires. */
    private UUID reflexTestDefinitionId;

    private boolean active = true;

    /** Human-readable description of this rule. */
    private String description;
}
