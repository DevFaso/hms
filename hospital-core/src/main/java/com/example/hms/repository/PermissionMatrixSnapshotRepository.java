package com.example.hms.repository;

import com.example.hms.enums.PermissionMatrixEnvironment;
import com.example.hms.model.PermissionMatrixSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionMatrixSnapshotRepository extends JpaRepository<PermissionMatrixSnapshot, UUID> {

    Optional<PermissionMatrixSnapshot> findFirstByEnvironmentOrderByVersionNumberDesc(PermissionMatrixEnvironment environment);

    List<PermissionMatrixSnapshot> findByEnvironmentOrderByVersionNumberDesc(PermissionMatrixEnvironment environment);
}