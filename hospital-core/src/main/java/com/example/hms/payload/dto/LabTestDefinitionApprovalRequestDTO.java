package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for Lab Director / Quality Manager approval actions
 * on a LabTestDefinition.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestDefinitionApprovalRequestDTO {

    /**
     * The approval action to perform.
     * Allowed values:
     * - SUBMIT_FOR_QA          (LAB_SCIENTIST, LAB_MANAGER — DRAFT → PENDING_QA_REVIEW)
     * - COMPLETE_QA_REVIEW     (QUALITY_MANAGER — PENDING_QA_REVIEW → PENDING_DIRECTOR_APPROVAL)
     * - APPROVE                (LAB_DIRECTOR — PENDING_DIRECTOR_APPROVAL → APPROVED)
     * - ACTIVATE               (LAB_DIRECTOR, LAB_MANAGER — APPROVED → ACTIVE)
     * - REJECT                 (LAB_DIRECTOR or QUALITY_MANAGER — any pending state → REJECTED)
     * - RETIRE                 (LAB_DIRECTOR — ACTIVE → RETIRED)
     */
    @NotBlank
    @Size(max = 40)
    private String action;

    /**
     * Required when action is REJECT. Explains why the definition was rejected.
     */
    @Size(max = 2048)
    private String rejectionReason;
}
