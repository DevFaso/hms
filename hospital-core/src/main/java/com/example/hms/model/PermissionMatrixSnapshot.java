package com.example.hms.model;

import com.example.hms.enums.PermissionMatrixEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permission_matrix_snapshots", uniqueConstraints = {
    @UniqueConstraint(name = "uk_permission_matrix_env_version", columnNames = {"environment", "version_number"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_snapshot_id")
    private UUID sourceSnapshotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 32)
    private PermissionMatrixEnvironment environment;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "matrix_json", nullable = false, columnDefinition = "TEXT")
    private String matrixJson;
}