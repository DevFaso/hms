package com.example.hms.model;

import com.example.hms.enums.CdsAcknowledgementAction;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Clinician acknowledgement (or override) of a Best-Practice Advisory card.
 * The rule engine uses these rows to suppress the same card on subsequent
 * patient-view evaluations until {@link #expiresAt} passes.
 */
@Entity
@Table(
    name = "cds_acknowledgements",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_cds_ack_patient_active", columnList = "patient_id,expires_at"),
        @Index(name = "idx_cds_ack_card_uuid",      columnList = "card_uuid")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"patient", "hospital", "user"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class CdsAcknowledgement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_cds_ack_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_cds_ack_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_cds_ack_user"))
    private User user;

    @Column(name = "card_uuid", length = 64)
    private String cardUuid;

    @Column(name = "card_summary", length = 500, nullable = false)
    private String cardSummary;

    @Column(length = 20, nullable = false)
    private String indicator;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private CdsAcknowledgementAction action;

    @Column(length = 1000)
    private String reason;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
