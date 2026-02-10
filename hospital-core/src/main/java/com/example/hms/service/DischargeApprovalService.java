package com.example.hms.service;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.payload.dto.discharge.DischargeApprovalDecisionDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeApprovalResponseDTO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface DischargeApprovalService {

    @Transactional
    DischargeApprovalResponseDTO requestDischarge(DischargeApprovalRequestDTO request);

    @Transactional
    DischargeApprovalResponseDTO approve(UUID approvalId, DischargeApprovalDecisionDTO decision);

    @Transactional
    DischargeApprovalResponseDTO reject(UUID approvalId, DischargeApprovalDecisionDTO decision);

    @Transactional
    DischargeApprovalResponseDTO cancel(UUID approvalId, UUID cancelledByStaffId, String reason);

    @Transactional(readOnly = true)
    DischargeApprovalResponseDTO getById(UUID approvalId);

    @Transactional(readOnly = true)
    List<DischargeApprovalResponseDTO> getActiveForPatient(UUID patientId);

    @Transactional(readOnly = true)
    List<DischargeApprovalResponseDTO> getPendingForHospital(UUID hospitalId);

    @Transactional(readOnly = true)
    List<DischargeApprovalResponseDTO> getByHospitalAndStatus(UUID hospitalId, DischargeStatus status);
}
