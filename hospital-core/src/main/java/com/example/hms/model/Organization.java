package com.example.hms.model;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.OrganizationPlatformService;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.EqualsAndHashCode;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
    name = "organizations",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_organization_code", columnNames = "code")
    },
    indexes = {
        @Index(name = "idx_organization_active", columnList = "active"),
        @Index(name = "idx_organization_type", columnList = "type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Organization extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Size(max = 500)
    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private OrganizationType type;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @OneToMany(mappedBy = "organization", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Hospital> hospitals = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "organization", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OrganizationSecurityPolicy> securityPolicies = new HashSet<>();

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();

    @Embedded
    @AttributeOverride(name = "ehrSystem", column = @Column(name = "ehr_system", length = 120))
    @AttributeOverride(name = "billingSystem", column = @Column(name = "billing_system", length = 120))
    @AttributeOverride(name = "inventorySystem", column = @Column(name = "inventory_system", length = 120))
    @AttributeOverride(name = "integrationNotes", column = @Column(name = "integration_notes", length = 255))
    @Builder.Default
    private PlatformServiceMetadata platformServiceMetadata = PlatformServiceMetadata.empty();

    @Builder.Default
    @OneToMany(mappedBy = "organization", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OrganizationPlatformService> platformServices = new HashSet<>();

    @Column(name = "primary_contact_email", length = 255)
    private String primaryContactEmail;

    @Column(name = "primary_contact_phone", length = 32)
    private String primaryContactPhone;

    @Column(name = "default_timezone", length = 120)
    private String defaultTimezone;

    @Column(name = "onboarding_notes", length = 1000)
    private String onboardingNotes;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (code != null) code = code.trim().toUpperCase();
        if (name != null) name = name.trim();
        if (description != null) description = description.trim();
        if (primaryContactEmail != null) primaryContactEmail = primaryContactEmail.trim();
        if (primaryContactPhone != null) primaryContactPhone = primaryContactPhone.trim();
        if (defaultTimezone != null) defaultTimezone = defaultTimezone.trim();
        if (onboardingNotes != null) onboardingNotes = onboardingNotes.trim();
    }

    public void addHospital(Hospital hospital) {
        if (hospital == null) return;
        hospitals.add(hospital);
        hospital.setOrganization(this);
    }

    public void removeHospital(Hospital hospital) {
        if (hospital == null) return;
        hospitals.remove(hospital);
        if (hospital.getOrganization() == this) {
            hospital.setOrganization(null);
        }
    }

    public void addSecurityPolicy(OrganizationSecurityPolicy policy) {
        if (policy == null) return;
        securityPolicies.add(policy);
        policy.setOrganization(this);
    }

    public void addPlatformService(OrganizationPlatformService service) {
        if (service == null) return;
        service.setOrganization(this);
        platformServices.add(service);
    }

    public void removePlatformService(OrganizationPlatformService service) {
        if (service == null) return;
        platformServices.remove(service);
        if (service.getOrganization() == this) {
            service.setOrganization(null);
        }
    }

    public void removeSecurityPolicy(OrganizationSecurityPolicy policy) {
        if (policy == null) return;
        securityPolicies.remove(policy);
        if (policy.getOrganization() == this) {
            policy.setOrganization(null);
        }
    }

    @Override
    public String toString() {
        return "Organization{" +
            "id=" + getId() +
            ", name='" + name + '\'' +
            ", code='" + code + '\'' +
            ", type=" + type +
            ", active=" + active +
            '}';
    }
}
