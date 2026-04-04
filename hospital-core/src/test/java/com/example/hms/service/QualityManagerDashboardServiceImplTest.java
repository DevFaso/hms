package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.dashboard.QualityManagerDashboardDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.service.impl.QualityManagerDashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QualityManagerDashboardServiceImplTest {

    @Mock private LabTestDefinitionRepository definitionRepository;
    @Mock private LabTestValidationStudyRepository studyRepository;
    @Mock private LabOrderRepository orderRepository;

    @InjectMocks private QualityManagerDashboardServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    @BeforeEach
    void setupDefaultStubs() {
        // Definition approval status counts
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW, HOSPITAL_ID)).thenReturn(7L);
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.DRAFT, HOSPITAL_ID)).thenReturn(4L);
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL, HOSPITAL_ID)).thenReturn(2L);
        when(definitionRepository.countByActiveTrueAndHospital_Id(HOSPITAL_ID)).thenReturn(30L);

        // Validation study counts
        when(studyRepository.countByLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(20L);
        when(studyRepository.countByPassedTrueAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(15L);
        when(studyRepository.countByPassedFalseAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(5L);
        when(studyRepository.countByHospitalAndStudyDateOnOrAfter(
                eq(HOSPITAL_ID), any(LocalDate.class))).thenReturn(6L);

        // Order counts
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.CANCELLED), any(), any())).thenReturn(3L);
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), any(), any())).thenReturn(12L);
    }

    // ── QA review queue ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getSummary returns correct QA review queue counts")
    void summaryReturnsQaReviewCounts() {
        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getPendingQaReview()).isEqualTo(7L);
        assertThat(dto.getDraftDefinitions()).isEqualTo(4L);
        assertThat(dto.getPendingDirectorApproval()).isEqualTo(2L);
        assertThat(dto.getActiveDefinitions()).isEqualTo(30L);
    }

    // ── Validation studies ───────────────────────────────────────────────────

    @Test
    @DisplayName("getSummary returns correct validation study totals")
    void summaryReturnsValidationStudyTotals() {
        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getTotalValidationStudies()).isEqualTo(20L);
        assertThat(dto.getPassedValidationStudies()).isEqualTo(15L);
        assertThat(dto.getFailedValidationStudies()).isEqualTo(5L);
        assertThat(dto.getValidationStudiesLast30Days()).isEqualTo(6L);
    }

    @Test
    @DisplayName("getSummary computes qualityPassRate correctly when resolved studies > 0")
    void summaryComputesPassRateWhenResolved() {
        // passed=15, failed=5 → resolved=20 → rate = 75.0
        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getQualityPassRate()).isNotNull();
        assertThat(dto.getQualityPassRate()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("getSummary sets qualityPassRate to null when no resolved studies")
    void summaryHasNullPassRateWhenNoResolvedStudies() {
        when(studyRepository.countByPassedTrueAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByPassedFalseAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);

        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getQualityPassRate()).isNull();
    }

    @Test
    @DisplayName("getSummary returns 100% pass rate when all studies passed")
    void summaryReturns100PassRateWhenAllPassed() {
        when(studyRepository.countByPassedTrueAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(10L);
        when(studyRepository.countByPassedFalseAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);

        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getQualityPassRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("getSummary returns 0% pass rate when all studies failed")
    void summaryReturns0PassRateWhenAllFailed() {
        when(studyRepository.countByPassedTrueAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByPassedFalseAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(8L);

        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getQualityPassRate()).isEqualTo(0.0);
    }

    // ── Non-conformance (order counts) ───────────────────────────────────────

    @Test
    @DisplayName("getSummary returns correct order counts")
    void summaryReturnsOrderCounts() {
        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersCancelledThisWeek()).isEqualTo(3L);
        assertThat(dto.getOrdersToday()).isEqualTo(12L);
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSummary sets hospitalId and asOfDate")
    void summaryHasHospitalIdAndDate() {
        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(dto.getAsOfDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("getSummary works correctly when all counts are zero")
    void summaryHandlesAllZeroCounts() {
        when(definitionRepository.countByApprovalStatusAndHospital_Id(any(), eq(HOSPITAL_ID))).thenReturn(0L);
        when(definitionRepository.countByActiveTrueAndHospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByPassedTrueAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByPassedFalseAndLabTestDefinition_Hospital_Id(HOSPITAL_ID)).thenReturn(0L);
        when(studyRepository.countByHospitalAndStudyDateOnOrAfter(eq(HOSPITAL_ID), any(LocalDate.class))).thenReturn(0L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.CANCELLED), any(), any())).thenReturn(0L);
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(eq(HOSPITAL_ID), any(), any())).thenReturn(0L);

        QualityManagerDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getPendingQaReview()).isZero();
        assertThat(dto.getTotalValidationStudies()).isZero();
        assertThat(dto.getQualityPassRate()).isNull();
        assertThat(dto.getOrdersToday()).isZero();
    }
}
