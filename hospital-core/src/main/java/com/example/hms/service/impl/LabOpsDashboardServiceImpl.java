package com.example.hms.service.impl;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.payload.dto.dashboard.LabOpsSummaryDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.service.LabOpsDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabOpsDashboardServiceImpl implements LabOpsDashboardService {

    private final LabOrderRepository orderRepository;

    /** Statuses that represent "active" (not terminal) orders. */
    private static final List<LabOrderStatus> ACTIVE_STATUSES = List.of(
            LabOrderStatus.ORDERED,
            LabOrderStatus.PENDING,
            LabOrderStatus.COLLECTED,
            LabOrderStatus.RECEIVED,
            LabOrderStatus.IN_PROGRESS,
            LabOrderStatus.RESULTED,
            LabOrderStatus.VERIFIED
    );

    @Override
    public LabOpsSummaryDTO getSummary(UUID hospitalId) {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);

        // Week window: Monday 00:00 → today 23:59:59
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDateTime weekStartDt = weekStart.atStartOfDay();

        // Month window: 1st of current month → today 23:59:59
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDateTime monthStartDt = monthStart.atStartOfDay();

        // ── Today counts ────────────────────────────────────────────────────
        long ordersToday = orderRepository
                .countByHospital_IdAndOrderDatetimeBetween(hospitalId, todayStart, todayEnd);
        long completedToday = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.COMPLETED, todayStart, todayEnd);
        long cancelledToday = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.CANCELLED, todayStart, todayEnd);

        // ── Week counts ─────────────────────────────────────────────────────
        long ordersThisWeek = orderRepository
                .countByHospital_IdAndOrderDatetimeBetween(hospitalId, weekStartDt, todayEnd);
        long completedThisWeek = orderRepository
                .countByHospital_IdAndStatusAndOrderDatetimeBetween(
                        hospitalId, LabOrderStatus.COMPLETED, weekStartDt, todayEnd);

        // ── Month counts ────────────────────────────────────────────────────
        long ordersThisMonth = orderRepository
                .countByHospital_IdAndOrderDatetimeBetween(hospitalId, monthStartDt, todayEnd);

        // ── Status breakdown (current active) ───────────────────────────────
        long statusOrdered = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.ORDERED));
        long statusPending = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.PENDING));
        long statusCollected = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.COLLECTED));
        long statusReceived = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.RECEIVED));
        long statusInProgress = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.IN_PROGRESS));
        long statusResulted = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.RESULTED));
        long statusVerified = orderRepository
                .countByHospitalIdAndStatusIn(hospitalId, List.of(LabOrderStatus.VERIFIED));

        // ── Priority breakdown (active orders only) ─────────────────────────
        long priorityRoutine = orderRepository
                .countByHospitalIdAndPriorityAndStatusIn(hospitalId, "ROUTINE", ACTIVE_STATUSES);
        long priorityUrgent = orderRepository
                .countByHospitalIdAndPriorityAndStatusIn(hospitalId, "URGENT", ACTIVE_STATUSES);
        long priorityStat = orderRepository
                .countByHospitalIdAndPriorityAndStatusIn(hospitalId, "STAT", ACTIVE_STATUSES);

        // ── Turnaround time ─────────────────────────────────────────────────
        Double avgTatToday = orderRepository.avgTurnaroundMinutes(hospitalId, todayStart, todayEnd);
        Double avgTatWeek = orderRepository.avgTurnaroundMinutes(hospitalId, weekStartDt, todayEnd);

        // ── Aging: active orders placed > 24 hours ago ──────────────────────
        LocalDateTime cutoff24h = LocalDateTime.now().minusHours(24);
        long ordersOlderThan24h = orderRepository
                .countByHospitalIdAndStatusInAndOrderDatetimeBefore(
                        hospitalId, ACTIVE_STATUSES, cutoff24h);

        return LabOpsSummaryDTO.builder()
                .hospitalId(hospitalId)
                .asOfDate(today)
                .ordersToday(ordersToday)
                .completedToday(completedToday)
                .cancelledToday(cancelledToday)
                .ordersThisWeek(ordersThisWeek)
                .completedThisWeek(completedThisWeek)
                .ordersThisMonth(ordersThisMonth)
                .statusOrdered(statusOrdered)
                .statusPending(statusPending)
                .statusCollected(statusCollected)
                .statusReceived(statusReceived)
                .statusInProgress(statusInProgress)
                .statusResulted(statusResulted)
                .statusVerified(statusVerified)
                .priorityRoutine(priorityRoutine)
                .priorityUrgent(priorityUrgent)
                .priorityStat(priorityStat)
                .avgTurnaroundMinutesToday(avgTatToday)
                .avgTurnaroundMinutesThisWeek(avgTatWeek)
                .ordersOlderThan24h(ordersOlderThan24h)
                .build();
    }
}
