package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Hospital;
import com.example.hms.model.embedded.PlatformOwnership;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "hospital_platform_service_links",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_hospital_service", columnNames = {"hospital_id", "organization_service_id"})
    },
    indexes = {
        @Index(name = "idx_hospital_service_enabled", columnList = "enabled")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, of = {"hospital", "organizationService"})
public class HospitalPlatformServiceLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_hospital_service_link_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_service_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_hospital_service_link_org_service"))
    private OrganizationPlatformService organizationService;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "credentials_reference", length = 120)
    private String credentialsReference;

    @Column(name = "override_endpoint", length = 255)
    private String overrideEndpoint;

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "link_owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "link_owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "link_data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "link_service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();
}
