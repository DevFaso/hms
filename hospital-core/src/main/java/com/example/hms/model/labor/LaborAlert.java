package com.example.hms.model.labor;

import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Embedded record of an alert generated while evaluating a partograph entry
 * or a delivery record. Mirrors {@link com.example.hms.model.postpartum.PostpartumObservationAlert}.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@Embeddable
public class LaborAlert {

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 32)
    private LaborAlertType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_severity", nullable = false, length = 16)
    private LaborAlertSeverity severity;

    @Column(name = "alert_code", length = 64)
    private String code;

    @Column(name = "alert_message", columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "triggered_by", length = 120)
    private String triggeredBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
