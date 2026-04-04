package com.example.hms.service.impl;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.dashboard.LabDirectorDashboardDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.service.LabDirectorDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabDirectorDashboardServiceImpl implements LabDirectorDashboardService {

    private final LabTestDefinitionRepository definitionRepository;
    private final LabTestValidationStudyRepository studyRepository;
    private final LabOrderRepository orderRepository;

    @Override
    public LabDirectorDashboardDTO getSummary(UUID hospitalId) {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);

        LocalDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L)
                .atStartOfDay();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        // ── Test Definition counts ──────────────────────────────────────────
        long pendingDirectorApproval = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL, hospitalId);

        long pendingQaReview = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW, hospitalId);

        long draftDefinitions = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.DRAFT, hospitalId);

        long activeDefinitions = definitionRepository
                .countByActiveTrueAndHospital_Id(hospitalId);

        // ── Validation Study counts ─────────────────────────────────────────
        long validationStudiesPendingApproval = studyRepository
                .countStudiesPendingDirectorApproval(hospitalId);

        long validationStudiesLast30Days = studyRepository
                .countByHospitalAndStudyDateAfter(hospitalId, thirtyDaysAgo);

        // ── Lab Order counts ────────────────────────────────────────────────
        long ordersToday = orderRepository
                .countByHospital_IdAndOrderDatetimeBetween(hospitalId, todayStart, todayEnd);

        long ordersCompletedToday = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.COMPLETED, todayStart, todayEnd);

        long ordersInProgress = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId,
                        List.of(LabOrderStatus.IN_PROGRESS, LabOrderStatus.RECEIVED));

        long ordersCancelledThisWeek = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.CANCELLED, weekStart, todayEnd);

        // ── TAT ─────────────────────────────────────────────────────────────
        Double avgTat = orderRepository.avgTurnaroundMinutes(hospitalId, todayStart, todayEnd);

        return LabDirectorDashboardDTO.builder()
                .hospitalId(hospitalId)
                .asOfDate(today)
                .pendingDirectorApproval(pendingDirectorApproval)
                .pendingQaReview(pendingQaReview)
                .draftDefinitions(draftDefinitions)
                .activeDefinitions(activeDefinitions)
                .validationStudiesPendingApproval(validationStudiesPendingApproval)
                .validationStudiesLast30Days(validationStudiesLast30Days)
                .ordersToday(ordersToday)
                .ordersCompletedToday(ordersCompletedToday)
                .ordersInProgress(ordersInProgress)
                .ordersCancelledThisWeek(ordersCancelledThisWeek)
                .avgTurnaroundMinutesToday(avgTat)
                .recentApprovalAudit(List.of()) // populated in a future audit integration
                .build();
    }
}
