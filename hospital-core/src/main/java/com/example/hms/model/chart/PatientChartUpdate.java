package com.example.hms.model.chart;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "patient_chart_updates",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_chart_update_version", columnNames = {"patient_id", "hospital_id", "version_number"})
    },
    indexes = {
        @Index(name = "idx_chart_update_patient", columnList = "patient_id"),
        @Index(name = "idx_chart_update_hospital", columnList = "hospital_id"),
        @Index(name = "idx_chart_update_version", columnList = "patient_id, hospital_id, version_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientChartUpdate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chart_update_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chart_update_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by_staff_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chart_update_staff"))
    private Staff recordedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chart_update_assignment"))
    private UserRoleHospitalAssignment assignment;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "update_reason", nullable = false, length = 512)
    private String updateReason;

    @Column(name = "summary", length = 1024)
    private String summary;

    @Builder.Default
    @Column(name = "include_sensitive", nullable = false)
    private boolean includeSensitive = false;

    @Builder.Default
    @Column(name = "notify_care_team", nullable = false)
    private boolean notifyCareTeam = false;

    @Builder.Default
    @Column(name = "section_count")
    private Integer sectionCount = 0;

    @Builder.Default
    @Column(name = "attachment_count")
    private Integer attachmentCount = 0;

    @Column(name = "notification_sent_at")
    private LocalDateTime notificationSentAt;

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "patient_chart_section_entries",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "chart_update_id", foreignKey = @ForeignKey(name = "fk_chart_section_update"))
    )
    @OrderColumn(name = "position")
    private List<PatientChartSectionEntry> sections = new ArrayList<>();

    @Builder.Default
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "patient_chart_attachments",
        schema = "clinical",
        joinColumns = @JoinColumn(name = "chart_update_id", foreignKey = @ForeignKey(name = "fk_chart_attachment_update"))
    )
    @OrderColumn(name = "position")
    private List<PatientChartAttachment> attachments = new ArrayList<>();

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (updateReason != null) {
            updateReason = updateReason.trim();
        }
        if (summary != null) {
            summary = summary.trim();
        }
        sectionCount = sections != null ? sections.size() : 0;
        attachmentCount = attachments != null ? attachments.size() : 0;
    }
}
