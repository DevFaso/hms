package com.example.hms.model.neonatal;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
@Embeddable
public class NewbornAssessmentAlert {

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 32)
    private NewbornAlertType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_severity", nullable = false, length = 16)
    private NewbornAlertSeverity severity;

    @Column(name = "alert_code", length = 64)
    private String code;

    @Column(name = "alert_message", columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "triggered_by", length = 120)
    private String triggeredBy;

    /** Stamped by the owning entity's addAlert: an @Embeddable never gets @PrePersist (D131). */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

}
