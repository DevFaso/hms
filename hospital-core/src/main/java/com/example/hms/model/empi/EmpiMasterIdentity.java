package com.example.hms.model.empi;

import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiResolutionState;
import com.example.hms.model.BaseEntity;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.TenantEntityListener;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "master_identities", schema = "empi",
    indexes = {
        @Index(name = "idx_empi_identity_patient", columnList = "patient_id"),
        @Index(name = "idx_empi_identity_org", columnList = "organization_id"),
        @Index(name = "idx_empi_identity_hospital", columnList = "hospital_id"),
        @Index(name = "idx_empi_identity_status", columnList = "status")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(TenantEntityListener.class)
@ToString(exclude = {"aliases", "primaryMerges", "secondaryMerges"})
@EqualsAndHashCode(callSuper = true, exclude = {"aliases", "primaryMerges", "secondaryMerges"})
public class EmpiMasterIdentity extends BaseEntity implements TenantScoped {

    @Column(name = "empi_number", length = 64, nullable = false, unique = true)
    private String empiNumber;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "hospital_id")
    private UUID hospitalId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private EmpiIdentityStatus status = EmpiIdentityStatus.ACTIVE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_state", length = 30)
    private EmpiResolutionState resolutionState = EmpiResolutionState.NEW;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "mrn_snapshot", length = 100)
    private String mrnSnapshot;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Builder.Default
    @OneToMany(mappedBy = "masterIdentity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<EmpiIdentityAlias> aliases = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "primaryIdentity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<EmpiMergeEvent> primaryMerges = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "secondaryIdentity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<EmpiMergeEvent> secondaryMerges = new HashSet<>();

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (empiNumber != null) {
            empiNumber = empiNumber.trim().toUpperCase();
        }
        if (sourceSystem != null) {
            sourceSystem = sourceSystem.trim();
        }
        if (mrnSnapshot != null) {
            mrnSnapshot = mrnSnapshot.trim();
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

    public void addAlias(EmpiIdentityAlias alias) {
        if (alias == null) return;
        aliases.add(alias);
        alias.setMasterIdentity(this);
    }
}
