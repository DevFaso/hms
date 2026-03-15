package com.example.hms.service;

import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.FrontDeskPatientSnapshotDTO;
import com.example.hms.payload.dto.InsuranceIssueDTO;
import com.example.hms.payload.dto.ReceptionDashboardSummaryDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReceptionService {

    ReceptionDashboardSummaryDTO getDashboardSummary(LocalDate date, UUID hospitalId);

    List<ReceptionQueueItemDTO> getQueue(LocalDate date, UUID hospitalId, String status,
                                         UUID departmentId, UUID providerId);

    FrontDeskPatientSnapshotDTO getPatientSnapshot(UUID patientId, UUID hospitalId);

    List<InsuranceIssueDTO> getInsuranceIssues(LocalDate date, UUID hospitalId);

    List<ReceptionQueueItemDTO> getPaymentsPending(LocalDate date, UUID hospitalId);

    FlowBoardDTO getFlowBoard(LocalDate date, UUID hospitalId, UUID departmentId);
}
