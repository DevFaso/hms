package com.example.hms.service;

import com.example.hms.payload.dto.clinical.PatientTrackerBoardDTO;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Service for the hospital-wide patient tracker board (MVP 5).
 */
public interface PatientTrackerService {

    /**
     * Build the tracker board for a given hospital and date.
     *
     * @param hospitalId   the hospital scope
     * @param departmentId optional department filter (null = all departments)
     * @param date         the date to query (null defaults to today)
     * @return a board DTO with patients grouped by status lanes
     */
    PatientTrackerBoardDTO getTrackerBoard(UUID hospitalId, UUID departmentId, LocalDate date);
}
