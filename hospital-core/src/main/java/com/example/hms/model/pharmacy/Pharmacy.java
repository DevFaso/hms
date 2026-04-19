package com.example.hms.model.pharmacy;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.enums.PharmacyType;
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
 * Registered pharmacy: hospital dispensary or community partner.
 * Supports Burkina-specific identifiers (license, facility code) alongside
 * optional US identifiers (NPI, NCPDP).
 */
@Entity
@Table(
    name = "pharmacies",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pharmacy_hospital", columnList = "hospital_id"),
        @Index(name = "idx_pharmacy_type", columnList = "pharmacy_type"),
        @Index(name = "idx_pharmacy_active", columnList = "active"),
        @Index(name = "idx_pharmacy_license", columnList = "license_number")
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

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pharmacy_hospital"))
    private Hospital hospital;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "pharmacy_type", nullable = false, length = 30)
    @Builder.Default
    private PharmacyType pharmacyType = PharmacyType.HOSPITAL_DISPENSARY;

    @Size(max = 50)
    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    @Size(max = 50)
    @Column(name = "facility_code", length = 50)
    private String facilityCode;

    @Size(max = 30)
    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Size(max = 255)
    @Column(name = "email", length = 255)
    private String email;

    @Size(max = 255)
    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Size(max = 255)
    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Size(max = 100)
    @Column(name = "city", length = 100)
    private String city;

    @Size(max = 100)
    @Column(name = "region", length = 100)
    private String region;

    @Size(max = 20)
    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Size(max = 60)
    @Column(name = "country", length = 60)
    @Builder.Default
    private String country = "Burkina Faso";

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_mode", nullable = false, length = 30)
    @Builder.Default
    private PharmacyFulfillmentMode fulfillmentMode = PharmacyFulfillmentMode.OUTPATIENT_HOSPITAL;

    @Size(max = 50)
    @Column(name = "npi", length = 50)
    private String npi;

    @Size(max = 20)
    @Column(name = "ncpdp", length = 20)
    private String ncpdp;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
