package com.example.hms.model.prescription;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionAlert {

    @Column(name = "alert_type", length = 40)
    private String alertType;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "reference_code", length = 120)
    private String referenceCode;

    @Builder.Default
    @Column(name = "blocking", nullable = false)
    private boolean blocking = false;

    @Builder.Default
    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;
}
