package com.example.hms.model.platform;

import com.example.hms.enums.ReportRunStatus;
import com.example.hms.model.BaseEntity;
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

import java.time.LocalDateTime;

/**
 * One emission of one report for one period (P3 #25a) — the exactly-once
 * ledger. Inserted as GENERATING before any generation happens: the
 * UNIQUE (report_definition_id, period_token) constraint is the claim,
 * so a second sweep instance hitting the same period gets a constraint
 * violation instead of a duplicate email. Check-then-act reminder stamps
 * cannot give that guarantee; a UNIQUE insert can.
 */
@Entity
@Table(name = "report_runs", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "definition")
public class ReportRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_definition_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_report_run_def"))
    private ReportDefinition definition;

    @Column(name = "period_token", nullable = false, length = 20)
    private String periodToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReportRunStatus status = ReportRunStatus.GENERATING;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
}
