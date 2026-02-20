package com.example.hms.service;

import com.example.hms.payload.dto.BulkShiftRequestDTO;
import com.example.hms.payload.dto.BulkShiftResultDTO;
import com.example.hms.payload.dto.StaffLeaveDecisionDTO;
import com.example.hms.payload.dto.StaffLeaveRequestDTO;
import com.example.hms.payload.dto.StaffLeaveResponseDTO;
import com.example.hms.payload.dto.StaffShiftRequestDTO;
import com.example.hms.payload.dto.StaffShiftResponseDTO;
import com.example.hms.payload.dto.StaffShiftStatusUpdateDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface StaffSchedulingService {

    StaffShiftResponseDTO scheduleShift(StaffShiftRequestDTO dto, Locale locale);

    /**
     * Bulk-schedule recurring shifts over a date range filtered by day-of-week.
     *
     * <p>When {@code dto.skipConflicts()} is {@code true} each conflicting date
     * is skipped silently and reported in {@link BulkShiftResultDTO#skipped()}.
     * When {@code false} the first conflict aborts the entire batch.
     */
    BulkShiftResultDTO bulkScheduleShifts(BulkShiftRequestDTO dto, Locale locale);

    StaffShiftResponseDTO updateShift(UUID shiftId, StaffShiftRequestDTO dto, Locale locale);

    StaffShiftResponseDTO updateShiftStatus(UUID shiftId, StaffShiftStatusUpdateDTO dto, Locale locale);

    List<StaffShiftResponseDTO> findShifts(UUID hospitalId,
                                           UUID departmentId,
                                           UUID staffId,
                                           LocalDate startDate,
                                           LocalDate endDate,
                                           Locale locale);

    StaffLeaveResponseDTO requestLeave(StaffLeaveRequestDTO dto, Locale locale);

    StaffLeaveResponseDTO decideLeave(UUID leaveId, StaffLeaveDecisionDTO dto, Locale locale);

    StaffLeaveResponseDTO cancelLeave(UUID leaveId, Locale locale);

    List<StaffLeaveResponseDTO> findLeaves(UUID hospitalId,
                                           UUID departmentId,
                                           UUID staffId,
                                           LocalDate startDate,
                                           LocalDate endDate,
                                           Locale locale);
}
