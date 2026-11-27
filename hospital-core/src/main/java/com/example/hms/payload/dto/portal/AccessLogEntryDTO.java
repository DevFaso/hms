package com.example.hms.payload.dto.portal;

import com.example.hms.enums.DisclosureCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Record of who accessed the patient's data")
public class AccessLogEntryDTO {

    @Schema(description = "Audit event ID")
    private UUID id;

    @Schema(description = "Who performed the action (user display name or system)")
    private String actor;

    @Schema(description = "Type of event (e.g. VIEW, UPDATE, EXPORT)")
    private String eventType;

    @Schema(description = "What was accessed (entity type)")
    private String entityType;

    @Schema(description = "Resource ID that was accessed")
    private String resourceId;

    @Schema(description = "Short description of the action")
    private String description;

    @Schema(description = "Status of the event (SUCCESS, FAILURE)")
    private String status;

    @Schema(description = "When the access occurred")
    private LocalDateTime timestamp;

    @Schema(description = "Role the actor held when they accessed the record.", example = "Doctor")
    private String actorRole;

    @Schema(description = "Hospital where the access happened.", example = "General Hospital")
    private String hospitalName;

    /**
     * What this access means to the patient, from
     * {@link com.example.hms.enums.DisclosureCategory}. Carries the
     * distinction the raw event type cannot: {@code PATIENT_ACCESS} is a
     * clinician opening the chart in one case and a disclosure to an
     * insurance scheme in another.
     */
    @Schema(description = "Patient-facing classification of the access.", example = "EMERGENCY_ACCESS")
    private DisclosureCategory category;

    @Schema(description = "True when the record went to someone outside the treating team "
        + "(another hospital, an insurer, or out as a file).", example = "true")
    private boolean externalDisclosure;

    /**
     * QA D3451 (r70): for a result released to the family ({@code RELEASED_TO_FAMILY}), who received it, as
     * displayed. Null on every other row, and when the release cannot be read.
     */
    @Schema(description = "Who a released result went to (a parent or guardian); null on other rows.")
    private String recipientName;

    @Schema(description = "How the recipient is related to the patient.", example = "PARENT")
    private String recipientRelationship;

    /** The category of the released result, never its value. */
    @Schema(description = "Category of the released result, never its value.", example = "HIV")
    private String resultCategory;

    /**
     * QA D3451 (proxy-read part): for a read through family access ({@code FAMILY_ACCESS}), which part of the
     * record was opened (APPOINTMENTS, MEDICATIONS, LAB_RESULTS, BILLING, RECORDS). Null on every other row.
     */
    @Schema(description = "Part of the record a family-access holder opened; null on other rows.", example = "MEDICATIONS")
    private String accessedSection;
}
