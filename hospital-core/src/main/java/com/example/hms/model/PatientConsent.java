package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "patient_consents",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_consent_patient_from_to", columnNames = {"patient_id", "from_hospital_id", "to_hospital_id"})
    },
    indexes = {
        @Index(name = "idx_consent_patient", columnList = "patient_id"),
        @Index(name = "idx_consent_from_hospital", columnList = "from_hospital_id"),
        @Index(name = "idx_consent_to_hospital", columnList = "to_hospital_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = "patient")
@EqualsAndHashCode(callSuper = true)
public class PatientConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consent_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consent_from_hospital"))
    private Hospital fromHospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_consent_to_hospital"))
    private Hospital toHospital;

    @Column(name = "consent_given", nullable = false)
    @Builder.Default
    private boolean consentGiven = true;

    @CreationTimestamp
    @Column(name = "consent_timestamp", nullable = false, updatable = false)
    private LocalDateTime consentTimestamp;

    @Column(name = "consent_expiration")
    private LocalDateTime consentExpiration;

    @Size(max = 1024)
    @Column(length = 1024)
    private String purpose;

    public boolean isConsentActive() {
        return consentGiven && (consentExpiration == null || consentExpiration.isAfter(LocalDateTime.now()));
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        if (fromHospital != null && toHospital != null && fromHospital.getId().equals(toHospital.getId())) {
            throw new IllegalStateException("fromHospital and toHospital must be different");
        }
        // (Optional) Ensure the patient is registered in the source hospital:
         if (!patient.isRegisteredInHospital(fromHospital.getId())) { throw new IllegalStateException("Patient not registered in source hospital"); }
    }
}
