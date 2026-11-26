package com.example.hms.payload.dto.medication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicationCatalogItemRequestDTO {

    @NotBlank(message = "{medicationCatalogItem.nameFr.required}")
    @Size(max = 255)
    private String nameFr;

    @Size(max = 255)
    @NotBlank(message = "{medicationCatalogItem.genericName.required}")
    private String genericName;

    @Size(max = 255)
    private String brandName;

    @Size(max = 20)
    private String atcCode;

    @Size(max = 100)
    private String form;

    @Size(max = 100)
    private String strength;

    @Size(max = 50)
    private String strengthUnit;

    @Size(max = 20)
    private String rxnormCode;

    /**
     * Optional ISMP "tall-man" lettering for confusable name pairs
     * (e.g. {@code "predniSONE"} vs {@code "prednisoLONE"}). Surfaced in
     * card detail by the {@code medication-prescribe} CDS hook when set.
     * Added in V93 (v1.0 / Clinical Safety / CDS Hooks expansion).
     */
    @Size(max = 200)
    private String tallManName;

    @Size(max = 100)
    private String route;

    @Size(max = 100)
    private String category;

    private boolean essentialList;

    private boolean controlled;

    /**
     * Policy 2026-11-20 (provisional): the sensitive category this medicine reveals (an
     * antiretroviral: HIV). Left out, an update keeps the tag the item has: this full-replace
     * edit must never clear it by omission. To clear it, send {@link #clearSensitivityCategory}.
     */
    private com.example.hms.enums.SensitivityCategory sensitivityCategory;

    /** True clears the sensitive category. Ignored when {@link #sensitivityCategory} is set. */
    private Boolean clearSensitivityCategory;

    // Round 18 (QA D935): the paediatric limits are written only through the
    // pharmacy's audited PUT /medication-catalog/{id}/pediatric-dosing.

    @Builder.Default
    private boolean active = true;

    @Size(max = 1000)
    private String description;

    /**
     * Go-live D2 (V385): why the entry is created, or why what prescribing and dispensing read is changed.
     * Required whenever the request proposes a version (a new entry; a change of name, ATC, form, strength,
     * route, RxNorm or controlled flag); the approver reads it and the history keeps it.
     */
    @Size(max = 1000)
    private String changeReason;

    /**
     * Owning hospital. Optional from V95 onwards: super-admins may omit
     * this field to create a <strong>global / platform catalog entry</strong>
     * (visible to every tenant) representing an item on Burkina Faso's
     * Liste Nationale des Médicaments Essentiels. Hospital admins must
     * supply their own hospitalId — the service layer enforces this
     * because bean validation alone cannot see the caller's role.
     */
    private UUID hospitalId;
}
