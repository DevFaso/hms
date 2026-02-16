package com.example.hms.repository;

import com.example.hms.model.FrontendAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FrontendAuditEventRepository extends JpaRepository<FrontendAuditEvent, UUID> {
}
