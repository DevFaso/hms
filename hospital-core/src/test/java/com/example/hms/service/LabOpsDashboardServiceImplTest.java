package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.payload.dto.dashboard.LabOpsSummaryDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.service.impl.LabOpsDashboardServiceImpl;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabOpsDashboardServiceImplTest {

    @Mock
    private LabOrderRepository orderRepository;

    @InjectMocks
    private LabOpsDashboardServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    @BeforeEach
    void setupDefaultStubs() {
        // Today counts
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), any(), any())).thenReturn(120L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.COMPLETED), any(), any())).thenReturn(80L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.CANCELLED), any(), any())).thenReturn(5L);

        // Status breakdown (single-status queries via countByHospitalIdAndStatusIn)
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.ORDERED)))).thenReturn(10L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.PENDING)))).thenReturn(8L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.COLLECTED)))).thenReturn(12L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.RECEIVED)))).thenReturn(15L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.IN_PROGRESS)))).thenReturn(20L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.RESULTED)))).thenReturn(7L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID),
                eq(List.of(LabOrderStatus.VERIFIED)))).thenReturn(3L);

        // Priority breakdown
        when(orderRepository.countByHospitalIdAndPriorityAndStatusIn(
                eq(HOSPITAL_ID), eq("ROUTINE"), anyList())).thenReturn(50L);
        when(orderRepository.countByHospitalIdAndPriorityAndStatusIn(
                eq(HOSPITAL_ID), eq("URGENT"), anyList())).thenReturn(18L);
        when(orderRepository.countByHospitalIdAndPriorityAndStatusIn(
                eq(HOSPITAL_ID), eq("STAT"), anyList())).thenReturn(7L);

        // TAT
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(32.5);

        // Aging
        when(orderRepository.countByHospitalIdAndStatusInAndOrderDatetimeBefore(
                eq(HOSPITAL_ID), anyList(), any())).thenReturn(4L);
    }

    @Test
    @DisplayName("getSummary returns today order counts")
    void summaryReturnsTodayCounts() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersToday()).isEqualTo(120L);
        assertThat(dto.getCompletedToday()).isEqualTo(80L);
        assertThat(dto.getCancelledToday()).isEqualTo(5L);
    }

    @Test
    @DisplayName("getSummary returns correct status breakdown")
    void summaryReturnsStatusBreakdown() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getStatusOrdered()).isEqualTo(10L);
        assertThat(dto.getStatusPending()).isEqualTo(8L);
        assertThat(dto.getStatusCollected()).isEqualTo(12L);
        assertThat(dto.getStatusReceived()).isEqualTo(15L);
        assertThat(dto.getStatusInProgress()).isEqualTo(20L);
        assertThat(dto.getStatusResulted()).isEqualTo(7L);
        assertThat(dto.getStatusVerified()).isEqualTo(3L);
    }

    @Test
    @DisplayName("getSummary returns correct priority breakdown")
    void summaryReturnsPriorityBreakdown() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getPriorityRoutine()).isEqualTo(50L);
        assertThat(dto.getPriorityUrgent()).isEqualTo(18L);
        assertThat(dto.getPriorityStat()).isEqualTo(7L);
    }

    @Test
    @DisplayName("getSummary returns average TAT")
    void summaryReturnsAvgTat() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getAvgTurnaroundMinutesToday()).isEqualTo(32.5);
        assertThat(dto.getAvgTurnaroundMinutesThisWeek()).isEqualTo(32.5);
    }

    @Test
    @DisplayName("getSummary returns null TAT when no completed orders")
    void summaryReturnsNullTatWhenNoCompleted() {
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(null);

        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getAvgTurnaroundMinutesToday()).isNull();
        assertThat(dto.getAvgTurnaroundMinutesThisWeek()).isNull();
    }

    @Test
    @DisplayName("getSummary returns aging order count")
    void summaryReturnsAgingCount() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersOlderThan24h()).isEqualTo(4L);
    }

    @Test
    @DisplayName("getSummary sets hospitalId and asOfDate")
    void summaryHasHospitalIdAndDate() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(dto.getAsOfDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("getSummary handles all-zero counts gracefully")
    void summaryHandlesAllZeros() {
        when(orderRepository.countByHospital_IdAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), any(), any())).thenReturn(0L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.COMPLETED), any(), any())).thenReturn(0L);
        when(orderRepository.countByHospital_IdAndStatusAndOrderDatetimeBetween(
                eq(HOSPITAL_ID), eq(LabOrderStatus.CANCELLED), any(), any())).thenReturn(0L);
        when(orderRepository.countByHospitalIdAndStatusIn(eq(HOSPITAL_ID), anyList())).thenReturn(0L);
        when(orderRepository.countByHospitalIdAndPriorityAndStatusIn(
                eq(HOSPITAL_ID), anyString(), anyList())).thenReturn(0L);
        when(orderRepository.avgTurnaroundMinutes(eq(HOSPITAL_ID), any(), any())).thenReturn(null);
        when(orderRepository.countByHospitalIdAndStatusInAndOrderDatetimeBefore(
                eq(HOSPITAL_ID), anyList(), any())).thenReturn(0L);

        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        assertThat(dto.getOrdersToday()).isZero();
        assertThat(dto.getCompletedToday()).isZero();
        assertThat(dto.getStatusInProgress()).isZero();
        assertThat(dto.getPriorityRoutine()).isZero();
        assertThat(dto.getAvgTurnaroundMinutesToday()).isNull();
        assertThat(dto.getOrdersOlderThan24h()).isZero();
    }

    @Test
    @DisplayName("getSummary returns week and month counts")
    void summaryReturnsWeekAndMonthCounts() {
        LabOpsSummaryDTO dto = service.getSummary(HOSPITAL_ID);

        // With the default stub returning 120L for all date ranges
        assertThat(dto.getOrdersThisWeek()).isEqualTo(120L);
        assertThat(dto.getOrdersThisMonth()).isEqualTo(120L);
        assertThat(dto.getCompletedThisWeek()).isEqualTo(80L);
    }
}
