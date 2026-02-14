package com.example.hms.model.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "identity_aliases", schema = "empi",
    uniqueConstraints = @UniqueConstraint(name = "uq_empi_alias_value", columnNames = {"alias_type", "alias_value"}),
    indexes = {
        @Index(name = "idx_empi_alias_master", columnList = "master_identity_id"),
        @Index(name = "idx_empi_alias_type", columnList = "alias_type")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "masterIdentity")
@EqualsAndHashCode(callSuper = true, exclude = "masterIdentity")
public class EmpiIdentityAlias extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "master_identity_id",
        foreignKey = @ForeignKey(name = "fk_empi_alias_master"),
        nullable = false)
    private EmpiMasterIdentity masterIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "alias_type", length = 50, nullable = false)
    private EmpiAliasType aliasType;

    @Column(name = "alias_value", length = 255, nullable = false)
    private String aliasValue;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private java.util.UUID createdBy;

    @Column(name = "updated_by")
    private java.util.UUID updatedBy;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (aliasValue != null) {
            aliasValue = aliasValue.trim();
        }
        if (sourceSystem != null) {
            sourceSystem = sourceSystem.trim();
        }
    }
}
