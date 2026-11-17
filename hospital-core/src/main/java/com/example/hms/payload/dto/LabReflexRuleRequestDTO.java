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
    // QA D1349 (round 30): required on create, declared so they are named at the
    // edge with any malformed value (the update is a partial edit, not validated).
    @jakarta.validation.constraints.NotNull(groups = com.example.hms.validation.OnCreate.class,
        message = "{lab.reflex.trigger.required}")
    private UUID triggerTestDefinitionId;

    /**
     * JSON condition string. Examples:
     * {"severityFlag":"ABNORMAL"}
     * {"thresholdOperator":"GT","thresholdValue":11.0}
     */
    @jakarta.validation.constraints.NotBlank(groups = com.example.hms.validation.OnCreate.class,
        message = "{lab.reflex.conditionRequired}")
    private String condition;

    /** Test definition to auto-order when condition fires. */
    @jakarta.validation.constraints.NotNull(groups = com.example.hms.validation.OnCreate.class,
        message = "{lab.reflex.reflex.required}")
    private UUID reflexTestDefinitionId;

    @Builder.Default
    private boolean active = true;

    /** Human-readable description of this rule. */
    /** QA D1387 (round 32): within its column, refused by name (not a generic constraint 400). */
    @jakarta.validation.constraints.Size(max = 512)
    private String description;
}
