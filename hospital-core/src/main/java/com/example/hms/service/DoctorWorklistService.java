package com.example.hms.service;

import com.example.hms.payload.dto.clinical.CriticalStripDTO;
import com.example.hms.payload.dto.clinical.DoctorWorklistItemDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service for the physician worklist and critical-strip data.
 */
public interface DoctorWorklistService {

    /**
     * Build the critical-strip DTO with 6 actionable counts.
     */
    CriticalStripDTO getCriticalStrip(UUID userId);

    /**
     * Build the merged physician worklist.
     *
     * @param userId     the doctor's user ID
     * @param status     optional encounter status filter
     * @param urgency    optional urgency filter
     * @param date       date to fetch appointments for (defaults to today when null)
     * @return list of worklist items
     */
    List<DoctorWorklistItemDTO> getWorklist(UUID userId, String status, String urgency, LocalDate date);
}
