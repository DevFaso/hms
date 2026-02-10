package com.example.hms.repository;

import com.example.hms.enums.SecurityPolicyApprovalStatus;
import com.example.hms.model.security.SecurityPolicyApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityPolicyApprovalRepository extends JpaRepository<SecurityPolicyApproval, UUID> {

    List<SecurityPolicyApproval> findByStatusOrderByRequiredByAsc(SecurityPolicyApprovalStatus status);
}
