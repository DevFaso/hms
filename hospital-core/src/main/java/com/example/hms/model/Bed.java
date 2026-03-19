package com.example.hms.model;

import com.example.hms.enums.BedStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(
    name = "beds",
    schema = "hospital",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_bed_ward_number", columnNames = {"ward_id", "bed_number"})
    },
    indexes = {
        @Index(name = "idx_bed_ward", columnList = "ward_id"),
        @Index(name = "idx_bed_status", columnList = "bed_status")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = "ward")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Bed extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ward_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_bed_ward"))
    private Ward ward;

    @NotBlank
    @Column(name = "bed_number", nullable = false, length = 20)
    private String bedNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "bed_status", nullable = false, length = 30)
    @Builder.Default
    private BedStatus status = BedStatus.AVAILABLE;

    @Column(name = "bed_type", length = 50)
    private String bedType;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
