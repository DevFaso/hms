package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.payload.dto.dashboard.LabDirectorDashboardDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.LabTestValidationStudyRepository;
import com.example.hms.service.impl.LabDirectorDashboardServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabDirectorDashboardServiceImplTest {

    @Mock private LabTestDefinitionRepository definitionRepository;
    @Mock private LabTestValidationStudyRepository studyRepository;
    @Mock private LabOrderRepository orderRepository;

    @InjectMocks private LabDirectorDashboardServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    @BeforeEach
    void setupDefaultStubs() {
        // Approval status counts
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL, HOSPITAL_ID)).thenReturn(3L);
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.PENDING_QA_REVIEW, HOSPITAL_ID)).thenReturn(2L);
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.DRAFT, HOSPITAL_ID)).thenReturn(5L);
        when(definitionRepository.countByActiveTrueAndHospital_Id(HOSPITAL_ID)).thenReturn(42L);

        // Validation study stubs
        when(studyRepository.countStudiesPendingDirectorApproval(HOSPITAL_ID)).thenReturn(1L);
        when(studyRepository.countByHospitalAndStudyDateOnOrAfter(eq(HOSPITAL_ID), any(LocalDate.class))).thenReturn(8L);

        // Order stubs
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(eq(HOSPITAL_ID), any(), any())).thenReturn(50L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.COMPLETED), any(), any())).thenReturn(30L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.CANCELLED), any(), any())).thenReturn(2L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID), anyList())).thenReturn(10L);
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(45.5);
    }

    @Test
    @DisplayName("getSummary returns correct approval queue counts")
    void summaryReturnsApprovalCounts() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getPendingDirectorApproval()).isEqualTo(3L);
        assertThat(dto.getPendingQaReview()).isEqualTo(2L);
        assertThat(dto.getDraftDefinitions()).isEqualTo(5L);
        assertThat(dto.getActiveDefinitions()).isEqualTo(42L);
    }

    @Test
    @DisplayName("getSummary returns correct validation study counts")
    void summaryReturnsValidationStudyCounts() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getValidationStudiesPendingApproval()).isEqualTo(1L);
        assertThat(dto.getValidationStudiesLast30Days()).isEqualTo(8L);
    }

    @Test
    @DisplayName("getSummary returns correct lab order counts")
    void summaryReturnsOrderCounts() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersToday()).isEqualTo(50L);
        assertThat(dto.getOrdersCompletedToday()).isEqualTo(30L);
        assertThat(dto.getOrdersInProgress()).isEqualTo(10L);
        assertThat(dto.getOrdersCancelledThisWeek()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getSummary includes average TAT when orders completed today exist")
    void summaryIncludesAvgTat() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getAvgTurnaroundMinutesToday()).isEqualTo(45.5);
    }

    @Test
    @DisplayName("getSummary sets avgTurnaroundMinutesToday to null when no orders completed today")
    void summaryHasNullTatWhenNoCompletedOrders() {
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(null);

        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getAvgTurnaroundMinutesToday()).isNull();
    }

    @Test
    @DisplayName("getSummary sets hospitalId and asOfDate")
    void summaryHasHospitalIdAndDate() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(dto.getAsOfDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("getSummary returns empty audit list")
    void summaryHasEmptyAuditList() {
        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getRecentApprovalAudit()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("getSummary works correctly when no pending approvals exist")
    void summaryWorksWithZeroPendingApprovals() {
        when(definitionRepository.countByApprovalStatusAndHospital_Id(
                LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL, HOSPITAL_ID)).thenReturn(0L);

        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getPendingDirectorApproval()).isZero();
    }

    @Test
    @DisplayName("getSummary works correctly when no orders exist today")
    void summaryWorksWithZeroOrdersToday() {
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(eq(HOSPITAL_ID), any(), any())).thenReturn(0L);
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(null);

        LabDirectorDashboardDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersToday()).isZero();
        assertThat(dto.getAvgTurnaroundMinutesToday()).isNull();
    }
}
