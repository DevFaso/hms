package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One FHIR Bulk Data Access {@code $export} invocation (P3 #24, roadmap
 * row 21 follow-on) — the durable replacement for the foundation pass's
 * in-memory job map, which queued jobs that could never finish and
 * forgot them on restart. The @Scheduled runner sweeps QUEUED rows;
 * terminal rows (COMPLETED / FAILED / CANCELLED) are kept, never
 * deleted — the status endpoint decides what each looks like on the
 * wire (a cancelled job polls as 404, per the bulk-data spec).
 */
@Entity
@Table(name = "fhir_bulk_export_jobs", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FhirBulkExportJob extends BaseEntity {

    /** Spec-level scope of a single $export invocation. */
    public enum Scope { SYSTEM, PATIENT, GROUP }

    public enum Status { QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private Scope scope;

    @Column(name = "since_instant")
    private Instant sinceInstant;

    /** Comma-joined {@code _type} filter; blank/null = every supported type. */
    @Column(name = "types", length = 500)
    private String types;

    @Column(name = "group_id", length = 100)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.QUEUED;

    /** The kickoff URL, echoed back as {@code request} in the manifest. */
    @Column(name = "request_url", length = 500)
    private String requestUrl;

    @Column(name = "requested_by_username", length = 255)
    private String requestedByUsername;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "processed_patients", nullable = false)
    @Builder.Default
    private int processedPatients = 0;

    @Column(name = "total_patients")
    private Integer totalPatients;

    public List<String> typeList() {
        if (types == null || types.isBlank()) return List.of();
        return Arrays.stream(types.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
