package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationCatalogItemResponseDTO {

    private UUID id;
    private String nameFr;
    private String genericName;
    private String brandName;
    private String atcCode;
    private String form;
    private String strength;
    private String strengthUnit;
    private String rxnormCode;
    /** Optional ISMP tall-man lettering, see request DTO. Added in V93. */
    private String tallManName;
    private String route;
    private String category;
    private boolean essentialList;
    private boolean controlled;
    /** Policy 2026-11-20: the sensitive category this medicine reveals; null for none. */
    private com.example.hms.enums.SensitivityCategory sensitivityCategory;
    /** QA D935: mg per kg per dose, or null when the catalogue holds no ceiling. */
    private java.math.BigDecimal pediatricMaxDoseMgPerKg;
    /** Round 18 (QA D935): the rest of the paediatric limits, their source and approval. */
    private java.math.BigDecimal pediatricMaxDailyMgPerKg;
    private java.math.BigDecimal pediatricMaxSingleDoseMg;
    private java.math.BigDecimal pediatricMaxDailyMg;
    private Integer pediatricManualReviewUnderMonths;
    private java.util.List<PediatricWeightBandDTO> pediatricWeightBands;
    private String pediatricDoseSource;
    private com.example.hms.enums.PediatricDoseLimitStatus pediatricDoseStatus;
    private UUID pediatricDoseReviewedBy;
    private LocalDateTime pediatricDoseReviewedAt;
    private boolean active;
    private String description;
    private UUID hospitalId;
    private String hospitalName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Go-live D2 (V385): the number of the formulary version in force, approved by a second person. Null: no
     * second person approved this entry yet (it predates the approval circuit); it works as before.
     */
    private Integer formularyApprovedVersion;
    /** Go-live D2 (V385): the same for the dose limits. */
    private Integer doseLimitApprovedVersion;
    /** Go-live D2 (V385): the formulary version awaiting a decision on this entry, if any. */
    private UUID pendingFormularyVersionId;
    /** Go-live D2 (V385): the dose-limit version awaiting a decision on this entry, if any. */
    private UUID pendingDoseLimitVersionId;
}
