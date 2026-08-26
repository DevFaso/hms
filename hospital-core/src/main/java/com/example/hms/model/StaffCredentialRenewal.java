package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One recorded credential renewal (Tier 2 item 40).
 *
 * <p>Exists because overwriting {@link Staff#getLicenseExpiryDate()} in place
 * destroys the only evidence that somebody practised past their expiry. The
 * question worth answering after an incident is not "is this clinician
 * licensed now" but "was this clinician licensed on the day they prescribed
 * that", and only a history answers it.
 *
 * <p>The previous values are stored on the row rather than reconstructed by
 * walking the chain: reconstruction breaks as soon as a row is corrected or
 * a licence is recorded out of order, and this table's whole purpose is to be
 * readable by somebody investigating years later.
 *
 * <p>Append-only in practice. Nothing in the service updates a row after it
 * is written.
 */
@Entity
@Table(
    name = "staff_credential_renewals",
    schema = "hospital",
    indexes = {
        @Index(name = "idx_credential_renewal_staff_recent", columnList = "staff_id, recorded_at"),
        @Index(name = "idx_credential_renewal_hospital", columnList = "hospital_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"staff", "hospital", "recordedBy"})
public class StaffCredentialRenewal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_credential_renewal_staff"))
    private Staff staff;

    /**
     * Denormalised from the staff member so the history can be listed and
     * tenant-scoped without joining, the same way other hospital-scoped
     * child tables here carry it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_credential_renewal_hospital"))
    private Hospital hospital;

    @Column(name = "previous_license_number", length = 100)
    private String previousLicenseNumber;

    /**
     * What the licence said immediately before this row was written. Null
     * when the staff member had no expiry on file at all — a first recording
     * rather than a renewal.
     */
    @Column(name = "previous_expiry_date")
    private LocalDate previousExpiryDate;

    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    /**
     * The new expiry. Not nullable: a renewal with no end date is not a
     * renewal, it is a deletion of the expiry rule.
     */
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    @Column(name = "note", length = 1000)
    private String note;

    /**
     * Who recorded it. Server identity, never client-asserted — the same
     * stance the signing, co-signing and pharmacist-verification ceremonies
     * take.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_credential_renewal_recorded_by"))
    private User recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
