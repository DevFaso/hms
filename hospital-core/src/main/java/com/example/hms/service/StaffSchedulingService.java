package com.example.hms.service;

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
