package com.example.hms.repository;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.model.PermissionMatrixAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PermissionMatrixAuditEventRepository extends JpaRepository<PermissionMatrixAuditEvent, UUID> {

    List<PermissionMatrixAuditEvent> findTop50ByOrderByCreatedAtDesc();

    List<PermissionMatrixAuditEvent> findTop50ByActionOrderByCreatedAtDesc(PermissionMatrixAuditAction action);
}