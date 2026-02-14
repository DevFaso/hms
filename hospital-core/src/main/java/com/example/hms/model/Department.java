package com.example.hms.model;

import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(
    name = "departments",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_dept_hospital_name", columnNames = {"hospital_id", "name"})
    },
    indexes = {
        @Index(name = "idx_dept_hospital", columnList = "hospital_id"),
        @Index(name = "idx_dept_assignment", columnList = "assignment_id"),
        @Index(name = "idx_dept_active", columnList = "is_active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital", "staffMembers", "headOfDepartment", "departmentTranslations", "treatments", "assignment"})
@EqualsAndHashCode(callSuper = true)
public class Department extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_department_assignment"))
    private UserRoleHospitalAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_department_hospital"))
    private Hospital hospital;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(length = 255, nullable = false)
    private String email;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "description", length = 2048)
    private String description;

    @Column(name = "bed_capacity")
    private Integer bedCapacity;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "head_of_department_staff_id",
        foreignKey = @ForeignKey(name = "fk_department_head"))
    private Staff headOfDepartment;

    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Staff> staffMembers = new HashSet<>();

    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DepartmentTranslation> departmentTranslations = new ArrayList<>();

    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @Builder.Default
    private Set<Treatment> treatments = new HashSet<>();

    @Embedded
    @AttributeOverride(name = "ownerTeam", column = @Column(name = "department_owner_team", length = 120))
    @AttributeOverride(name = "ownerContactEmail", column = @Column(name = "department_owner_contact_email", length = 255))
    @AttributeOverride(name = "dataSteward", column = @Column(name = "department_data_steward", length = 120))
    @AttributeOverride(name = "serviceLevel", column = @Column(name = "department_service_level", length = 60))
    @Builder.Default
    private PlatformOwnership ownership = PlatformOwnership.empty();

    @Embedded
    @AttributeOverride(name = "ehrSystem", column = @Column(name = "department_ehr_system", length = 120))
    @AttributeOverride(name = "billingSystem", column = @Column(name = "department_billing_system", length = 120))
    @AttributeOverride(name = "inventorySystem", column = @Column(name = "department_inventory_system", length = 120))
    @AttributeOverride(name = "integrationNotes", column = @Column(name = "department_integration_notes", length = 255))
    @Builder.Default
    private PlatformServiceMetadata platformServiceMetadata = PlatformServiceMetadata.empty();

    @Builder.Default
    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<DepartmentPlatformServiceLink> platformServiceLinks = new HashSet<>();

    public void addPlatformServiceLink(DepartmentPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        link.setDepartment(this);
        platformServiceLinks.add(link);
    }

    public void removePlatformServiceLink(DepartmentPlatformServiceLink link) {
        if (link == null) {
            return;
        }
        boolean removed = platformServiceLinks.removeIf(existing ->
            existing == link || Objects.equals(existing.getId(), link.getId()));
        if (removed) {
            link.setDepartment(null);
        }
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        // Assignment hospital must match department hospital
        if (assignment == null || assignment.getHospital() == null || hospital == null
            || !Objects.equals(assignment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Department.assignment.hospital must match department.hospital");
        }

        if (headOfDepartment != null && headOfDepartment.getHospital() != null
            && !Objects.equals(headOfDepartment.getHospital().getId(), hospital.getId())) {
            throw new IllegalStateException("Head of department must belong to the same hospital");
        }
    }

}
