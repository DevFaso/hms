package com.example.hms.model;

import com.example.hms.enums.LabSpecimenStatus;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "lab_specimens",
    schema = "lab",
    indexes = {
        @Index(name = "idx_lab_specimens_lab_order",  columnList = "lab_order_id"),
        @Index(name = "idx_lab_specimens_accession",  columnList = "accession_number"),
        @Index(name = "idx_lab_specimens_status",     columnList = "status")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"labOrder"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabSpecimen extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_lab_specimens_order"))
    private LabOrder labOrder;

    /** Unique human-readable identifier used for tracking (e.g. ACC-20240115-A1B2C). */
    @NotBlank
    @Column(name = "accession_number", nullable = false, unique = true, length = 50)
    private String accessionNumber;

    /** Machine-readable barcode payload (1D/2D). */
    @Column(name = "barcode_value", length = 100)
    private String barcodeValue;

    /** Type of biological specimen (Blood, Urine, Tissue, etc.). */
    @Column(name = "specimen_type", length = 50)
    private String specimenType;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    /** ID of the user (staff) who collected the specimen. */
    @Column(name = "collected_by_id")
    private UUID collectedById;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** ID of the user (staff) who received the specimen at the lab. */
    @Column(name = "received_by_id")
    private UUID receivedById;

    /** Physical location inside the lab (e.g. "Bench 3 – Haematology"). */
    @Column(name = "current_location", length = 100)
    private String currentLocation;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private LabSpecimenStatus status = LabSpecimenStatus.PENDING;

    @Column(name = "notes", length = 2048)
    private String notes;
}
