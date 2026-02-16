package com.example.hms.model.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
    name = "organization_platform_services",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_org_service_type", columnNames = {"organization_id", "service_type"})
    },
    indexes = {
        @Index(name = "idx_org_service_type", columnList = "service_type"),
        @Index(name = "idx_org_service_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class OrganizationPlatformService extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_org_platform_service_org"))
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 40)
    private PlatformServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PlatformServiceStatus status = PlatformServiceStatus.PENDING;

    @Column(name = "provider", length = 120)
    private String provider;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(name = "documentation_url", length = 255)
    private String documentationUrl;

    @Column(name = "api_key_reference", length = 120)
    private String apiKeyReference;

    @Column(name = "managed_by_platform", nullable = false)
    @Builder.Default
    private boolean managedByPlatform = true;

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "service_owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "service_owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "service_data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();

    @Embedded
    @AttributeOverride(name = "ehrSystem", column = @Column(name = "service_ehr_system", length = 120))
    @AttributeOverride(name = "billingSystem", column = @Column(name = "service_billing_system", length = 120))
    @AttributeOverride(name = "inventorySystem", column = @Column(name = "service_inventory_system", length = 120))
    @AttributeOverride(name = "integrationNotes", column = @Column(name = "service_integration_notes", length = 255))
    @Builder.Default
    private PlatformServiceMetadata metadata = PlatformServiceMetadata.empty();

    @Builder.Default
    @OneToMany(mappedBy = "organizationService", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<HospitalPlatformServiceLink> hospitalLinks = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "organizationService", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<DepartmentPlatformServiceLink> departmentLinks = new HashSet<>();

    public void addHospitalLink(HospitalPlatformServiceLink link) {
        if (link == null) return;
        link.setOrganizationService(this);
        hospitalLinks.add(link);
    }

    public void removeHospitalLink(HospitalPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        boolean removed = hospitalLinks.removeIf(existing ->
            existing == link || Objects.equals(existing.getId(), link.getId()));
        if (removed) {
            link.setOrganizationService(null);
        }
    }

    public void addDepartmentLink(DepartmentPlatformServiceLink link) {
        if (link == null) return;
        link.setOrganizationService(this);
        departmentLinks.add(link);
    }

    public void removeDepartmentLink(DepartmentPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        boolean removed = departmentLinks.removeIf(existing ->
            existing == link || Objects.equals(existing.getId(), link.getId()));
        if (removed) {
            link.setOrganizationService(null);
        }
    }

    public boolean isLinkedTo(Hospital hospital) {
        if (hospital == null) return false;
        return hospitalLinks.stream().anyMatch(link -> hospital.getId().equals(link.getHospital().getId()));
    }
}
