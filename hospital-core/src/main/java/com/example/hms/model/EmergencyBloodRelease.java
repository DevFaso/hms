package com.example.hms.model;

import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One bag going out on an emergency release (V266). One row per use, tied to its unit: a request
 * with several bags has several rows, and a bag released twice keeps both. Never updated.
 *
 * <p>It sits beside the crossmatch rather than on it: a serologically incompatible release records a
 * decision and leaves the laboratory's finding as it was.
 */
@Entity
@Table(
    name = "emergency_blood_releases",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_emergency_release_request", columnList = "request_id, blood_unit_id, issued_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"request", "bloodUnit", "hospital", "authoriser", "issuedBy", "reason"})
public class EmergencyBloodRelease extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_emergency_release_request"))
    private TransfusionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blood_unit_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_emergency_release_unit"))
    private BloodUnit bloodUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_emergency_release_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_type", nullable = false, length = 40)
    private EmergencyReleaseType releaseType;

    /** Why, in the words of whoever decided: a clinical narrative, so encrypted. Null with no decision. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** Who decided or authorised. Null when the release needed no decision. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authoriser_user_id",
        foreignKey = @ForeignKey(name = "fk_emergency_release_authoriser"))
    private User authoriser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_user_id",
        foreignKey = @ForeignKey(name = "fk_emergency_release_issuer"))
    private User issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    /** The patient's record held a positive antibody screen when the bag went out. */
    @Column(name = "antibody_warning", nullable = false)
    @Builder.Default
    private Boolean antibodyWarning = Boolean.FALSE;

    /**
     * The system showed O negative of this product in stock and the person releasing O positive stated
     * it cannot be obtained in time. The stock can be wrong, so it asks and never refuses outright.
     */
    @Column(name = "negative_unobtainable_stated", nullable = false)
    @Builder.Default
    private Boolean negativeUnobtainableStated = Boolean.FALSE;

    /** The O negative bags the system showed then, by the number on the bag. */
    @Column(name = "negative_units_shown", columnDefinition = "TEXT")
    private String negativeUnitsShown;
}
