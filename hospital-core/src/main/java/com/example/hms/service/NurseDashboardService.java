package com.example.hms.service;

import com.example.hms.payload.dto.PatientResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NurseDashboardService {
    List<PatientResponseDTO> getPatientsForNurse(UUID nurseUserId, UUID hospitalId, LocalDate inhouseDate);
}
