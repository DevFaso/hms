package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

@Entity
@Table(
    name = "lab_inventory_items",
    schema = "lab",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_lab_inventory_code",
            columnNames = {"hospital_id", "item_code"})
    },
    indexes = {
        @Index(name = "idx_lab_inventory_hospital", columnList = "hospital_id"),
        @Index(name = "idx_lab_inventory_category", columnList = "category"),
        @Index(name = "idx_lab_inventory_low_stock", columnList = "quantity, reorder_threshold")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"hospital"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabInventoryItem extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @NotBlank
    @Size(max = 100)
    @Column(name = "item_code", nullable = false, length = 100)
    private String itemCode;

    @Size(max = 100)
    @Column(length = 100)
    private String category;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_lab_inventory_hospital"))
    private Hospital hospital;

    @NotNull
    @Min(0)
    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Size(max = 50)
    @Column(length = 50)
    private String unit;

    @NotNull
    @Min(0)
    @Column(name = "reorder_threshold", nullable = false)
    @Builder.Default
    private Integer reorderThreshold = 0;

    @Size(max = 255)
    @Column(length = 255)
    private String supplier;

    @Size(max = 100)
    @Column(name = "lot_number", length = 100)
    private String lotNumber;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Size(max = 2048)
    @Column(length = 2048)
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
