package com.example.hms.model;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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

/**
 * One superseded patient address (Tier 2 item 38): the address that was
 * valid UNTIL this row's {@code createdAt}. The current address lives on
 * {@link Patient}; a patient who never moved has zero rows here. Written by
 * the patient-update paths whenever the address actually changes — never
 * for the initial fill-in of a blank address.
 *
 * <p>The address line is encrypted at rest exactly like its source column
 * on {@link Patient}.
 */
@Entity
@Table(
    name = "patient_address_history",
    schema = "clinical",
    indexes = @Index(name = "idx_addr_hist_patient", columnList = "patient_id, created_at")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(exclude = {"patient"})
public class PatientAddressHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_addr_hist_patient"))
    private Patient patient;

    /**
     * The composed mailing address as it stood — the line a clinician
     * reads. Deliberately NOT a column-for-column clone of the source: a
     * superseded address has no structured query surface, so the snapshot
     * carries the composed line plus city/country for coarse filtering.
     */
    @Column(name = "address", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String address;

    @Size(max = 100)
    @Column(name = "city", length = 100)
    private String city;

    @Size(max = 100)
    @Column(name = "country", length = 100)
    private String country;
}
