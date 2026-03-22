package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "platform_system_alerts", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SystemAlert extends BaseEntity {

    @NotBlank
    @Size(max = 60)
    @Column(name = "alert_type", nullable = false, length = 60)
    private String alertType;

    @NotBlank
    @Size(max = 30)
    @Column(name = "severity", nullable = false, length = 30)
    private String severity;

    @NotBlank
    @Size(max = 500)
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    @Size(max = 120)
    @Column(name = "source", length = 120)
    private String source;

    @Column(name = "acknowledged")
    @Builder.Default
    private boolean acknowledged = false;

    @Size(max = 120)
    @Column(name = "acknowledged_by", length = 120)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "resolved")
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
