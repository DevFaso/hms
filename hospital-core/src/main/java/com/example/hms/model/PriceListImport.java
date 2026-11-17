package com.example.hms.model;

import com.example.hms.enums.PriceListImportStatus;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Billing-money plan PR 4 (V304, scorecard M1): the trace of one price-list spreadsheet checked (DRY_RUN) or applied
 * (APPLIED) at a hospital: its file name, the SHA-256 of its bytes and the row counts. An applied file is recognised
 * by its SHA-256 and never applied twice at the same hospital. Written once, never edited.
 */
@Entity
@Table(
    name = "price_list_imports",
    schema = "billing",
    indexes = @Index(name = "idx_price_list_import_hospital_created", columnList = "hospital_id, created_at")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
// Identity is the id, as for every BaseEntity row; adding columns adds no equality.
@SuppressWarnings("java:S2160")
public class PriceListImport extends BaseEntity implements TenantScoped {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    /** As uploaded, trimmed to 255 characters; shown, never used as a path. */
    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    /** Lower-case hex SHA-256 of the file's bytes. */
    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private PriceListImportStatus status;

    @Column(name = "total_rows", nullable = false, updatable = false)
    private int totalRows;

    @Column(name = "created_count", nullable = false, updatable = false)
    private int createdCount;

    @Column(name = "new_price_count", nullable = false, updatable = false)
    private int newPriceCount;

    @Column(name = "unchanged_count", nullable = false, updatable = false)
    private int unchangedCount;

    @Column(name = "refused_count", nullable = false, updatable = false)
    private int refusedCount;

    @Column(name = "imported_by", updatable = false)
    private UUID importedBy;

    @Override
    public UUID getTenantOrganizationId() {
        return null;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospitalId;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return null;
    }

    /** The service always sets the hospital; this only fills a missing one from the pinned scope. */
    @Override
    public void applyTenantScope(HospitalContext context) {
        if (hospitalId == null && context != null) {
            hospitalId = ActingScopeResolver.pinnedHospitalIdOf(context);
        }
    }
}
