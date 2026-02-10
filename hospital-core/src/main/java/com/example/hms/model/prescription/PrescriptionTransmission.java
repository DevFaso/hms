package com.example.hms.model.prescription;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Prescription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "prescription_transmissions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_prescription_transmission_rx", columnList = "prescription_id"),
        @Index(name = "idx_prescription_transmission_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionTransmission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_prescription_transmission_rx"))
    private Prescription prescription;

    @Column(name = "channel", length = 40)
    private String channel;

    @Column(name = "destination", length = 255)
    private String destination;

    @Column(name = "destination_reference", length = 120)
    private String destinationReference;

    @Column(name = "status", length = 40)
    private String status;

    @Column(name = "status_reason", columnDefinition = "TEXT")
    private String statusReason;

    @Column(name = "attempt_count")
    private Integer attemptCount;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @Column(name = "payload", columnDefinition = "JSONB")
    private String payload;

    @PrePersist
    @PreUpdate
    public void normalize() {
        if (attemptCount == null) {
            attemptCount = 0;
        }
    }
}
