package com.example.hms.model;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.enums.PermissionMatrixEnvironment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permission_matrix_audit_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64)
    private PermissionMatrixAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "left_environment", length = 32)
    private PermissionMatrixEnvironment leftEnvironment;

    @Enumerated(EnumType.STRING)
    @Column(name = "right_environment", length = 32)
    private PermissionMatrixEnvironment rightEnvironment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private PermissionMatrixSnapshot snapshot;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "initiated_by", length = 255)
    private String initiatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "matrix_json", columnDefinition = "TEXT")
    private String matrixJson;
}