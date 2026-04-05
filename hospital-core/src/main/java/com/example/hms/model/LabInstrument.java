package com.example.hms.model;

import com.example.hms.enums.InstrumentStatus;
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
    name = "lab_instruments",
    schema = "lab",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_lab_instrument_serial",
            columnNames = {"hospital_id", "serial_number"})
    },
    indexes = {
        @Index(name = "idx_lab_instrument_hospital", columnList = "hospital_id"),
        @Index(name = "idx_lab_instrument_department", columnList = "department_id"),
        @Index(name = "idx_lab_instrument_status", columnList = "status")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"hospital", "department"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabInstrument extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    @Size(max = 255)
    @Column(length = 255)
    private String manufacturer;

    @Size(max = 255)
    @Column(name = "model_number", length = 255)
    private String modelNumber;

    @NotBlank
    @Size(max = 100)
    @Column(name = "serial_number", nullable = false, length = 100)
    private String serialNumber;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_lab_instrument_hospital"))
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_lab_instrument_department"))
    private Department department;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InstrumentStatus status = InstrumentStatus.ACTIVE;

    @Column(name = "installation_date")
    private LocalDate installationDate;

    @Column(name = "last_calibration_date")
    private LocalDate lastCalibrationDate;

    @Column(name = "next_calibration_date")
    private LocalDate nextCalibrationDate;

    @Column(name = "last_maintenance_date")
    private LocalDate lastMaintenanceDate;

    @Column(name = "next_maintenance_date")
    private LocalDate nextMaintenanceDate;

    @Size(max = 2048)
    @Column(length = 2048)
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
