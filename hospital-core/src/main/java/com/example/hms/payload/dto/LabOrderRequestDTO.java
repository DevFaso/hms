package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
// The all-args constructor is package-private on purpose: a PUBLIC one is
// auto-detected as Jackson's properties creator, and binding through a
// creator never calls a setter — which is where the presence flag below is
// set, so an explicit null could not be told from an absent field. The
// builder (same package) still uses it; nothing constructs this DTO
// positionally from outside. The flag itself is bookkeeping, never a wire
// field, and is ignored here as well as on the field so no client can spoof
// presence.
@JsonIgnoreProperties("performingHospitalIdPresent")
public class LabOrderRequestDTO {

    private UUID id;

    @NotNull(message = "{labOrder.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    private UUID encounterId;

    @NotBlank(message = "{labOrder.testName.required}")
    private String testName;

    private String testCode;

    @NotBlank(message = "{labOrder.status.required}")
    private String status;

    private String priority;

    @NotBlank(message = "{labOrder.clinicalIndication.required}")
    @Size(max = 2048, message = "{labOrder.clinicalIndication.size}")
    private String clinicalIndication;

    @NotBlank(message = "{labOrder.medicalNecessityNote.required}")
    @Size(max = 2048, message = "{labOrder.medicalNecessityNote.size}")
    private String medicalNecessityNote;

    private String notes;

    @NotBlank(message = "{labOrder.primaryDiagnosisCode.required}")
    @Schema(description = "Primary ICD-10 diagnosis code supporting medical necessity")
    private String primaryDiagnosisCode;

    @Builder.Default
    @Schema(description = "Additional ICD-10 diagnosis codes reinforcing medical necessity")
    private List<String> additionalDiagnosisCodes = new ArrayList<>();

    @Schema(description = "Channel used to document or transmit the lab order (ELECTRONIC, PORTAL, PHONE, FAX, EMAIL, WRITTEN, WALK_IN, OTHER)")
    @NotBlank(message = "{labOrder.orderChannel.required}")
    private String orderChannel;

    @Schema(description = "Free-text descriptor when orderChannel is OTHER")
    private String orderChannelOther;

    @Schema(description = "Whether identical documentation was shared with the destination laboratory. "
            + "When null the service auto-defaults: true for PORTAL/ELECTRONIC, false otherwise.")
    private Boolean documentationSharedWithLab;

    @Schema(description = "Reference or tracking identifier for the documentation shared with the lab")
    private String documentationReference;

    @Schema(description = "Ordering provider NPI override if it differs from the staff profile")
    @Pattern(regexp = "\\d{10}", message = "{labOrder.orderingProviderNpi.pattern}")
    private String orderingProviderNpi;

    @Schema(description = "Electronic signature attestation payload from the ordering provider")
    @NotBlank(message = "{labOrder.providerSignature.required}")
    private String providerSignature;

    @Schema(description = "Timestamp of the provider's electronic signature. Defaults to now if absent.")
    private LocalDateTime signedAt;

    @Schema(description = "Indicates if the order is governed by a standing order protocol")
    @Builder.Default
    private Boolean standingOrder = Boolean.FALSE;

    private LocalDateTime standingOrderExpiresAt;

    private LocalDateTime standingOrderLastReviewedAt;

    private Integer standingOrderReviewIntervalDays;

    private String standingOrderReviewNotes;

    private LocalDateTime orderDatetime;

    private LocalDateTime completedAt;

    private List<String> testResults;

    @NotNull(message = "{labOrder.orderingStaffId.required}")
    private UUID orderingStaffId;

    @NotNull(message = "{labOrder.labTestDefinitionId.required}")
    private UUID labTestDefinitionId;

    @NotNull(message = "{labOrder.assignmentId.required}")
    private UUID assignmentId;

    @Schema(description = "Laboratory (hospital) that performs the test when it is not this hospital's own. "
            + "On PUT the three states are distinct: omitted keeps the current laboratory, an explicit "
            + "null (or this hospital's own id) brings the test back in-house, any other id routes it there.")
    private UUID performingHospitalId;

    /**
     * Whether the payload carried {@code performingHospitalId} at all.
     *
     * <p>Jackson calls a setter only for a key that is present, so an omitted
     * field (leave the routing alone — the shape every pre-B1 client sends)
     * is distinguishable from an explicit {@code null} (bring the test back
     * in-house). Without this flag the two collapse and an outsourced order
     * can never be brought home. Not part of the JSON in either direction.
     */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean performingHospitalIdPresent;

    public void setPerformingHospitalId(UUID performingHospitalId) {
        this.performingHospitalId = performingHospitalId;
        this.performingHospitalIdPresent = Boolean.TRUE;
    }

    /** True when the caller named the performing laboratory, even as {@code null}. */
    public boolean hasPerformingHospitalId() {
        return Boolean.TRUE.equals(performingHospitalIdPresent);
    }

    /**
     * The builder marks the field present exactly as the setter does, so a
     * test that writes {@code .performingHospitalId(null)} expresses an
     * explicit null and one that omits the call expresses an absent field.
     */
    public static class LabOrderRequestDTOBuilder {
        public LabOrderRequestDTOBuilder performingHospitalId(UUID performingHospitalId) {
            this.performingHospitalId = performingHospitalId;
            this.performingHospitalIdPresent = Boolean.TRUE;
            return this;
        }
    }
}
