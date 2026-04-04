package com.example.hms.service.impl;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.dashboard.QualityManagerDashboardDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.service.QualityManagerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QualityManagerDashboardServiceImpl implements QualityManagerDashboardService {

    private final LabTestDefinitionRepository definitionRepository;
    private final LabTestValidationStudyRepository studyRepository;
    private final LabOrderRepository orderRepository;

    @Override
    public QualityManagerDashboardDTO getSummary(UUID hospitalId) {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);

        LocalDateTime weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L)
                .atStartOfDay();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        // ── Test Definition counts ──────────────────────────────────────────
        long pendingQaReview = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW, hospitalId);

        long draftDefinitions = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.DRAFT, hospitalId);

        long pendingDirectorApproval = definitionRepository
                .countByApprovalStatusAndHospital_Id(
                        LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL, hospitalId);

        long activeDefinitions = definitionRepository
                .countByActiveTrueAndHospital_Id(hospitalId);

        // ── Validation study metrics ────────────────────────────────────────
        long totalStudies = studyRepository
                .countByLabTestDefinition_Hospital_Id(hospitalId);

        long passedStudies = studyRepository
                .countByPassedTrueAndLabTestDefinition_Hospital_Id(hospitalId);

        long failedStudies = studyRepository
                .countByPassedFalseAndLabTestDefinition_Hospital_Id(hospitalId);

        long resolvedStudies = passedStudies + failedStudies;
        Double qualityPassRate = resolvedStudies > 0
                ? (passedStudies * 100.0) / resolvedStudies
                : null;

        long studiesLast30Days = studyRepository
                .countByHospitalAndStudyDateAfter(hospitalId, thirtyDaysAgo);

        // ── Non-conformance (cancelled orders) ──────────────────────────────
        long cancelledThisWeek = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.CANCELLED, weekStart, todayEnd);

        long ordersToday = orderRepository
                .countByHospital_IdAndOrderDatetimeBetween(hospitalId, todayStart, todayEnd);

        return QualityManagerDashboardDTO.builder()
                .hospitalId(hospitalId)
                .asOfDate(today)
                .pendingQaReview(pendingQaReview)
                .draftDefinitions(draftDefinitions)
                .pendingDirectorApproval(pendingDirectorApproval)
                .activeDefinitions(activeDefinitions)
                .totalValidationStudies(totalStudies)
                .passedValidationStudies(passedStudies)
                .failedValidationStudies(failedStudies)
                .qualityPassRate(qualityPassRate)
                .validationStudiesLast30Days(studiesLast30Days)
                .ordersCancelledThisWeek(cancelledThisWeek)
                .ordersToday(ordersToday)
                .build();
    }
}
