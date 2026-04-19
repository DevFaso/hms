package com.example.hms.model.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a pharmacy (hospital dispensary or partner/community pharmacy).
 * Three-tier model: Tier 1 (hospital dispensary), Tier 2 (partner), Tier 3 (unaffiliated).
 */
@Entity
@Table(
    name = "pharmacies",
    schema = "hospital",
    indexes = {
        @Index(name = "idx_pharmacy_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pharmacy_active", columnList = "active"),
        @Index(name = "idx_pharmacy_tier", columnList = "tier"),
        @Index(name = "idx_pharmacy_city", columnList = "city")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Pharmacy extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @Size(max = 100)
    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    @Size(max = 50)
    @Column(length = 50)
    private String phone;

    @Size(max = 255)
    private String email;

    @Size(max = 500)
    @Column(name = "address_line1", length = 500)
    private String addressLine1;

    @Size(max = 500)
    @Column(name = "address_line2", length = 500)
    private String addressLine2;

    @Size(max = 255)
    private String city;

    @Size(max = 255)
    private String region;

    @Size(max = 100)
    @Column(length = 100)
    @Builder.Default
    private String country = "Burkina Faso";

    private Double latitude;
    private Double longitude;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_mode", nullable = false, length = 50)
    private PharmacyFulfillmentMode fulfillmentMode;

    /** Tier 1 = hospital dispensary, Tier 2 = partner, Tier 3 = unaffiliated. */
    @Column(nullable = false)
    @Builder.Default
    private int tier = 1;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pharmacy_hospital"))
    private Hospital hospital;

    @Column(name = "partner_agreement", nullable = false)
    @Builder.Default
    private boolean partnerAgreement = false;

    @Size(max = 255)
    @Column(name = "partner_contact")
    private String partnerContact;

    /** Communication channel: SMS, WHATSAPP, PORTAL, API. */
    @Size(max = 50)
    @Column(name = "exchange_method", length = 50)
    @Builder.Default
    private String exchangeMethod = "SMS";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
