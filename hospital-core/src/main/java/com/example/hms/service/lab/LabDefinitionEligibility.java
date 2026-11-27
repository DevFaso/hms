package com.example.hms.service.lab;

import com.example.hms.model.LabTestDefinition;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;

/** A previously activated version remains valid for historical orders after retirement. */
public final class LabDefinitionEligibility {
    private LabDefinitionEligibility() { }
    public static boolean canResultOrderedVersion(LabTestDefinition definition) {
        return definition != null && (definition.getApprovalStatus() == LabTestDefinitionApprovalStatus.ACTIVE
            || definition.getApprovalStatus() == LabTestDefinitionApprovalStatus.RETIRED);
    }
}
