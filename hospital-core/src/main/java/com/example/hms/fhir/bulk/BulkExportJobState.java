package com.example.hms.fhir.bulk;

import com.example.hms.fhir.bulk.FhirBulkExportService.BulkExportStatus;
import com.example.hms.fhir.bulk.FhirBulkExportService.Scope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable in-memory state for a single FHIR Bulk Data Access
 * ($export) job — roadmap row 21 foundation pass.
 *
 * <p>This is the placeholder for the persistent
 * {@code fhir_bulk_export_jobs} table that ships with the row-21
 * follow-on. Field shape mirrors the columns the table will need so
 * the next pass only swaps the in-memory map for a JPA repository
 * without touching the operation provider's surface.
 */
public final class BulkExportJobState {

    private final UUID jobId;
    private final UUID hospitalId;
    private final Scope scope;
    private final Instant since;
    private final List<String> types;
    private final String groupId;
    private final BulkExportStatus status;
    private final Instant requestedAt;
    private final Instant completedAt;
    private final String errorMessage;

    public BulkExportJobState(
        UUID jobId,
        UUID hospitalId,
        Scope scope,
        Instant since,
        List<String> types,
        String groupId,
        BulkExportStatus status,
        Instant requestedAt,
        Instant completedAt,
        String errorMessage
    ) {
        this.jobId = jobId;
        this.hospitalId = hospitalId;
        this.scope = scope;
        this.since = since;
        this.types = types;
        this.groupId = groupId;
        this.status = status;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.errorMessage = errorMessage;
    }

    public BulkExportJobState withStatus(BulkExportStatus newStatus) {
        Instant completed = (newStatus == BulkExportStatus.COMPLETED
            || newStatus == BulkExportStatus.FAILED
            || newStatus == BulkExportStatus.CANCELLED) ? Instant.now() : this.completedAt;
        return new BulkExportJobState(
            jobId, hospitalId, scope, since, types, groupId,
            newStatus, requestedAt, completed, errorMessage
        );
    }

    public UUID getJobId() { return jobId; }
    public UUID getHospitalId() { return hospitalId; }
    public Scope getScope() { return scope; }
    public Instant getSince() { return since; }
    public List<String> getTypes() { return types; }
    public String getGroupId() { return groupId; }
    public BulkExportStatus getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorMessage() { return errorMessage; }
}
