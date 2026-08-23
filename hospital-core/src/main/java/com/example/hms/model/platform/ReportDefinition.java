package com.example.hms.model.platform;

import com.example.hms.enums.ReportPeriod;
import com.example.hms.enums.ReportType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Arrays;
import java.util.List;

/**
 * One scheduled-report configuration (P3 #25a) — the Dhis2FacilityConfig
 * shape: a per-hospital row the sweep iterates. Deactivate-never-delete;
 * runs reference their definition forever.
 */
@Entity
@Table(name = "report_definitions", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "hospital")
public class ReportDefinition extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_report_def_hospital"))
    private Hospital hospital;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 40)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 20)
    private ReportPeriod period;

    /** Comma-joined recipient email addresses. Aggregate-only content. */
    @Column(name = "recipients", nullable = false, length = 1000)
    private String recipients;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    public List<String> recipientList() {
        if (recipients == null || recipients.isBlank()) return List.of();
        return Arrays.stream(recipients.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
