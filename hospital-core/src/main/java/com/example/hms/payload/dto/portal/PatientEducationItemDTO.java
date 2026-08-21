package com.example.hms.payload.dto.portal;

import com.example.hms.enums.EducationCategory;
import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.enums.EducationResourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One education resource assigned to the authenticated patient, joined with
 * their own progress on it.
 *
 * <p>The staff-facing {@code PatientEducationProgressResponseDTO} carries only
 * {@code resourceId}, which forces every consumer to N+1 back to the resource
 * endpoint. This DTO denormalizes the resource fields a patient actually needs
 * to read the material, so the portal list renders from one call.
 *
 * <p>Provider-only fields ({@code providerNotes}, {@code providerId},
 * {@code discussedWithProviderAt}) are deliberately omitted — clinician
 * commentary about a patient is not part of the patient-facing contract.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationItemDTO {

    // ── Progress (this patient's engagement) ──
    private UUID progressId;
    private EducationComprehensionStatus comprehensionStatus;
    private Integer progressPercentage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime lastAccessedAt;
    private Integer rating;
    private String feedback;
    private Boolean needsClarification;
    private String clarificationRequest;
    private Boolean confirmedUnderstanding;

    // ── Resource (the material itself) ──
    private UUID resourceId;
    private String title;
    private String description;
    private EducationResourceType resourceType;
    private EducationCategory category;
    private String contentUrl;
    private String textContent;
    private String thumbnailUrl;
    private String videoUrl;
    private Integer estimatedDuration;
    private List<String> tags;
    private String primaryLanguage;
    private Boolean isWarningSignContent;
}
