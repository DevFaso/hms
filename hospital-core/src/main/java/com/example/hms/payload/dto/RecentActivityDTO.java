package com.example.hms.payload.dto;

import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aggregate response for {@code GET /super-admin/recent-activity?limit=N},
 * the F5 follow-up from {@code docs/super-admin-cross-tenant-design.md}.
 *
 * <p>Bundles the nine cross-tenant {@code recent-*} feeds the super-admin
 * dashboard's "Recent clinical activity" panel needs into a single
 * round-trip, replacing the eight independent subscriptions in
 * {@code super-admin.ts}'s {@code loadAll()}. Each field carries the
 * exact same DTO type the per-feed endpoint returns, so the frontend
 * just unpacks {@code response.encounters}, {@code response.consultations},
 * etc. and assigns them into the existing signal slots — no shape change
 * on the consumer side.</p>
 *
 * <p>Each list is sorted by its entity's clinical-time field
 * (encounterDate / orderDatetime / admissionDateTime / submittedAt /
 * timelineStartDate / resultDate / createdAt for prescriptions —
 * intentionally; see {@code SuperAdminDashboardServiceImpl.getRecentPrescriptions}).
 * The {@code Locale} from the request is propagated to fields that need
 * message-source localisation, mirroring the single-feed endpoints.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Aggregated recent-activity feed for the super-admin dashboard.")
public class RecentActivityDTO {

    @Schema(description = "Recent encounters across all tenants.")
    private List<EncounterResponseDTO> encounters;

    @Schema(description = "Recent consultations across all tenants.")
    private List<ConsultationResponseDTO> consultations;

    @Schema(description = "Recent lab orders across all tenants.")
    private List<LabOrderResponseDTO> labOrders;

    @Schema(description = "Recent lab results across all tenants.")
    private List<LabResultResponseDTO> labResults;

    @Schema(description = "Recent lab test definitions (catalogue, cross-tenant).")
    private List<LabTestDefinitionResponseDTO> labTestDefinitions;

    @Schema(description = "Recent admissions across all tenants.")
    private List<AdmissionResponseDTO> admissions;

    @Schema(description = "Recent prescriptions across all tenants.")
    private List<PrescriptionResponseDTO> prescriptions;

    @Schema(description = "Recent treatment plans across all tenants.")
    private List<TreatmentPlanResponseDTO> treatmentPlans;

    @Schema(description = "Recent general referrals across all tenants.")
    private List<GeneralReferralResponseDTO> referrals;
}
