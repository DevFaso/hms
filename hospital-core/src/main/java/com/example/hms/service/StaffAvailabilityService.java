package com.example.hms.service;

import com.example.hms.payload.dto.StaffAvailabilityRequestDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

public interface StaffAvailabilityService {
    StaffAvailabilityResponseDTO create(StaffAvailabilityRequestDTO dto, Locale locale);
    boolean isStaffAvailable(UUID staffId, LocalDateTime appointmentDateTime);

}
