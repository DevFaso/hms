package com.example.hms.model.empi;

import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.model.BaseEntity;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "merge_events", schema = "empi",
    indexes = {
        @Index(name = "idx_empi_merge_primary", columnList = "primary_identity_id"),
        @Index(name = "idx_empi_merge_secondary", columnList = "secondary_identity_id"),
        @Index(name = "idx_empi_merge_org", columnList = "organization_id"),
        @Index(name = "idx_empi_merge_hospital", columnList = "hospital_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"primaryIdentity", "secondaryIdentity"})
public class EmpiMergeEvent extends BaseEntity implements TenantScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "primary_identity_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_empi_merge_primary"))
    private EmpiMasterIdentity primaryIdentity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "secondary_identity_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_empi_merge_secondary"))
    private EmpiMasterIdentity secondaryIdentity;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "merge_type", length = 30)
    private EmpiMergeType mergeType;

    @Column(name = "resolution", length = 50)
    private String resolution;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "undo_token", length = 100)
    private String undoToken;

    @Column(name = "merged_by")
    private UUID mergedBy;

    @Column(name = "merged_at")
    private OffsetDateTime mergedAt;

    @PrePersist
    private void onPersist() {
        if (mergedAt == null) {
            mergedAt = OffsetDateTime.now();
        }
    }

    @Override
    public UUID getTenantOrganizationId() {
        return organizationId;
    }

    @Override
    public UUID getTenantHospitalId() {
        return hospitalId;
    }

    @Override
    public UUID getTenantDepartmentId() {
        return departmentId;
    }

    @Override
    public void applyTenantScope(HospitalContext context) {
        if (context == null) {
            return;
        }
        if (organizationId == null && context.getActiveOrganizationId() != null) {
            organizationId = context.getActiveOrganizationId();
        }
        if (hospitalId == null && context.getActiveHospitalId() != null) {
            hospitalId = context.getActiveHospitalId();
        }
        if (departmentId == null && !context.getPermittedDepartmentIds().isEmpty()) {
            departmentId = context.getPermittedDepartmentIds().iterator().next();
        }
    }
}
