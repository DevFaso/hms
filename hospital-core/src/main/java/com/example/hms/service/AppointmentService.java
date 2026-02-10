package com.example.hms.service;

import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AppointmentFilterDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface AppointmentService {
    // GET BY PATIENT USERNAME, filtered/scoped by user
    List<AppointmentResponseDTO> getAppointmentsByPatientUsername(String patientUsername, Locale locale, String username);

    // CREATE
    com.example.hms.payload.dto.AppointmentSummaryDTO createAppointment(AppointmentRequestDTO appointmentRequestDTO, Locale locale, String username);

    // GET BY ID - scoped by user
    AppointmentResponseDTO getAppointmentById(UUID id, Locale locale, String username);

    // GET ALL for the current user (role, hospital etc. will be handled in implementation)
    List<AppointmentResponseDTO> getAppointmentsForUser(String username, Locale locale);

    // UPDATE
    AppointmentResponseDTO updateAppointment(UUID id, AppointmentRequestDTO appointmentRequestDTO, Locale locale, String username);

    // DELETE
    void deleteAppointment(UUID id, Locale locale, String username);

    // GET BY PATIENT ID, filtered/scoped by user
    List<AppointmentResponseDTO> getAppointmentsByPatientId(UUID patientId, Locale locale, String username);

    // GET BY STAFF ID, filtered/scoped by user
    List<AppointmentResponseDTO> getAppointmentsByStaffId(UUID staffId, Locale locale, String username);

    // Confirm or cancel, with user context
    AppointmentResponseDTO confirmOrCancelAppointment(UUID appointmentId, String action, Locale locale, String username);

    // Search with custom filters and pagination
    Page<AppointmentResponseDTO> searchAppointments(AppointmentFilterDTO filter, Pageable pageable, Locale locale, String username);

    // LEGACY/ADMIN: Get by doctor for super admin/analytics (no user context)
    List<AppointmentResponseDTO> getAppointmentsByDoctorId(UUID staffId, Locale locale);

    // WITH CONTEXT: Get by doctor, but only what the user is allowed to see
    List<AppointmentResponseDTO> getAppointmentsByDoctorId(UUID staffId, Locale locale, String username);

}
