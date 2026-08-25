package com.example.hms.model;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.RhFactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

/**
 * One physical bag of blood held by the facility (Tier 2 item 28).
 *
 * <p>This is a UNIT REGISTRY, not a stock ledger and not a donor record. It
 * exists because there is nothing to crossmatch or issue without it. Where the
 * unit came from is a free-text {@link #source} — a supplier or regional blood
 * bank — because donor recruitment, screening and the donation chain belong to
 * a blood-bank LIS, not to an EHR.
 *
 * <p>{@link #request} is nullable on purpose: a facility that keeps its own
 * fridge stock receives units against no request, while one drawing per-request
 * from an external bank receives them against a specific one. Both are
 * representable, so the module does not force an inventory model the site has
 * not chosen.
 */
@Entity
@Table(
    name = "blood_units",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_blood_unit_request", columnList = "request_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"hospital", "request"})
public class BloodUnit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_blood_unit_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id",
        foreignKey = @ForeignKey(name = "fk_blood_unit_request"))
    private TransfusionRequest request;

    /** The label on the bag. Unique per hospital — enforced by the database. */
    @Size(max = 60)
    @Column(name = "unit_number", nullable = false, length = 60)
    private String unitNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 30)
    private BloodProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "abo_group", nullable = false, length = 3)
    private AboGroup aboGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "rh_factor", nullable = false, length = 10)
    private RhFactor rhFactor;

    @Column(name = "volume_ml")
    private Integer volumeMl;

    @Column(name = "collected_on")
    private LocalDate collectedOn;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Size(max = 200)
    @Column(name = "source", length = 200)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private BloodUnitStatus status = BloodUnitStatus.AVAILABLE;

    @Size(max = 500)
    @Column(name = "discard_reason", length = 500)
    private String discardReason;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * Expiry is evaluated against the CALLER's date rather than stored as a
     * status, because a unit does not become expired when someone looks at it.
     * The EXPIRED status exists for units explicitly retired in stock.
     */
    public boolean isExpiredOn(LocalDate date) {
        return expiresOn != null && !expiresOn.isAfter(date);
    }

    /** A unit that can still be committed to a patient. */
    public boolean isAssignable() {
        return status == BloodUnitStatus.AVAILABLE || status == BloodUnitStatus.RETURNED;
    }

    public boolean isTerminal() {
        return status == BloodUnitStatus.TRANSFUSED
            || status == BloodUnitStatus.DISCARDED
            || status == BloodUnitStatus.EXPIRED;
    }
}
