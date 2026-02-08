package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.Department;
import com.example.hms.model.embedded.PlatformOwnership;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "department_platform_service_links",
    schema = "platform",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_department_service", columnNames = {"department_id", "organization_service_id"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, of = {"department", "organizationService"})
public class DepartmentPlatformServiceLink extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_department_service_link_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_service_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_department_service_link_org_service"))
    private OrganizationPlatformService organizationService;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "credentials_reference", length = 120)
    private String credentialsReference;

    @Column(name = "override_endpoint", length = 255)
    private String overrideEndpoint;

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "dept_link_owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "dept_link_owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "dept_link_data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "dept_link_service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();
}
