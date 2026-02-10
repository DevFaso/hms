package com.example.hms.model;

import com.example.hms.enums.AdvanceDirectiveStatus;
import com.example.hms.enums.AdvanceDirectiveType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "advance_directives",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_directive_patient", columnList = "patient_id"),
        @Index(name = "idx_directive_hospital", columnList = "hospital_id"),
        @Index(name = "idx_directive_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class AdvanceDirective extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_directive_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_directive_hospital"))
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "directive_type", length = 80, nullable = false)
    private AdvanceDirectiveType directiveType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AdvanceDirectiveStatus status = AdvanceDirectiveStatus.ACTIVE;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "witness_name", length = 255)
    private String witnessName;

    @Column(name = "physician_name", length = 255)
    private String physicianName;

    @Column(name = "document_location", length = 255)
    private String documentLocation;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @PrePersist
    @PreUpdate
    void ensureDefaults() {
        if (status == null) {
            status = AdvanceDirectiveStatus.ACTIVE;
        }
    }
}
