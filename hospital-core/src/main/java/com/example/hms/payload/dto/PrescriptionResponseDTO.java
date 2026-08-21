package com.example.hms.payload.dto;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrescriptionResponseDTO {

    private UUID id;

    private UUID patientId;
    private String patientFullName;
    private String patientEmail;

    private UUID staffId;
    private String staffFullName;

    private UUID encounterId;
    private UUID hospitalId;
    /**
     * Display name of the prescription's hospital.
     * Required by the super-admin cross-tenant list view
     * (docs/super-admin-cross-tenant-design.md) so the global "Hospital"
     * column can render without an N+1 lookup.
     */
    private String hospitalName;

    private String medicationName;
    private String medicationDisplayName;

    private String dosage;
    private String frequency;
    private String duration;
    private String route;
    private String instructions;
    private String notes;

    private String status;

    /**
     * Signature evidence (P2 #16). Null on a SIGNED prescription means it was
     * signed before V118 and cannot be verified — deliberately distinguishable
     * from a prescription that carries a real digest.
     */
    private String signatureValue;
    private String signatureAlgorithm;
    private java.time.LocalDateTime signedAt;
    private java.util.UUID signedByStaffId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * CDS rule-engine cards produced when the prescription was last
     * created or updated. Empty when the engine had nothing to flag.
     * Null on read-only responses where the engine did not run.
     */
    private List<CdsCard> cdsAdvisories;
}
