package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.SecurityPolicyApprovalSummaryDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineExportDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineRequestDTO;
import com.example.hms.payload.dto.superadmin.SecurityPolicyBaselineResponseDTO;
import java.util.List;

public interface SecurityPolicyGovernanceService {

    SecurityPolicyBaselineResponseDTO createBaseline(SecurityPolicyBaselineRequestDTO request);

    List<SecurityPolicyApprovalSummaryDTO> listPendingApprovals();

    SecurityPolicyBaselineExportDTO exportLatestBaseline();
}
