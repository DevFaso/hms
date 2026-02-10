package com.example.hms.controller;

import com.example.hms.payload.dto.AppointmentFilterDTO;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import com.example.hms.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/appointments")
@Tag(name = "Appointment Management", description = "Endpoints for creating and managing patient appointments with role-based treatment access.")
@RequiredArgsConstructor
public class AppointmentController {

    // ---- LIST BY PATIENT USERNAME ----
    @GetMapping("/patients/username/{patientUsername}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'PATIENT')")
    public ResponseEntity<List<AppointmentResponseDTO>> getAppointmentsByPatientUsername(
        @PathVariable String patientUsername,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.getAppointmentsByPatientUsername(patientUsername, locale, getUsername(authentication))
        );
    }

    private final AppointmentService appointmentService;
    private final MessageSource messageSource;

    // Helper to get username (or a custom UserPrincipal with more info)
    private String getUsername(Authentication auth) {
        return auth.getName();
    }

    // ---- CREATE ----
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'PATIENT')")
    public ResponseEntity<AppointmentSummaryDTO> createAppointment(
        @Valid @RequestBody AppointmentRequestDTO request,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        AppointmentSummaryDTO created = appointmentService.createAppointment(request, locale, getUsername(authentication));
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    // ---- UPDATE ----
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    public ResponseEntity<AppointmentResponseDTO> updateAppointment(
        @PathVariable UUID id,
        @Valid @RequestBody AppointmentRequestDTO request,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.updateAppointment(id, request, locale, getUsername(authentication))
        );
    }

    // ---- STATUS ----
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST')")
    public ResponseEntity<AppointmentResponseDTO> updateAppointmentStatus(
        @PathVariable UUID id,
        @RequestParam(name = "action") String action,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.confirmOrCancelAppointment(id, action, locale, getUsername(authentication))
        );
    }

    // ---- GET BY ID ----
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'PATIENT')")
    public ResponseEntity<AppointmentResponseDTO> getAppointmentById(
        @PathVariable UUID id,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.getAppointmentById(id, locale, getUsername(authentication))
        );
    }

    // ---- SEARCH ----
    @PostMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'PATIENT')")
    public ResponseEntity<Page<AppointmentResponseDTO>> searchAppointments(
        @RequestBody(required = false) AppointmentFilterDTO filter,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "10") int size,
        @RequestParam(name = "sort", defaultValue = "appointmentDate,desc") String sort,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);
        Sort sortSpec = parseSort(sort);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1), sortSpec);
        Page<AppointmentResponseDTO> result = appointmentService.searchAppointments(filter, pageable, locale, getUsername(authentication));
        return ResponseEntity.ok(result);
    }

    // ---- LIST ALL ----
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    public ResponseEntity<List<AppointmentResponseDTO>> getAllAppointments(
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.getAppointmentsForUser(getUsername(authentication), locale)
        );
    }

    // ---- DELETE ----
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    public ResponseEntity<String> deleteAppointment(
        @PathVariable UUID id,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        appointmentService.deleteAppointment(id, locale, getUsername(authentication));
        String message = messageSource.getMessage("appointment.deleted", new Object[]{id}, locale);
        return ResponseEntity.ok(message);
    }

    // ---- LIST BY PATIENT ----
    @GetMapping("/patients/{patientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'PATIENT')")
    public ResponseEntity<List<AppointmentResponseDTO>> getAppointmentsByPatientId(
        @PathVariable UUID patientId,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        return ResponseEntity.ok(
            appointmentService.getAppointmentsByPatientId(patientId, locale, getUsername(authentication))
        );
    }

    // ---- LIST BY STAFF ----
    @GetMapping("/staff/{staffId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    public ResponseEntity<List<AppointmentResponseDTO>> getAppointmentsByStaffId(
        @PathVariable UUID staffId,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        return ResponseEntity.ok(
            loadAppointmentsForStaff(staffId, lang, authentication)
        );
    }

    /**
     * List appointments by nurse.
     * Admin, Nurse only.
     */
    @GetMapping("/nurse/{staffId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'NURSE', 'MIDWIFE', 'HOSPITAL_ADMIN')")
    public ResponseEntity<List<AppointmentResponseDTO>> getAppointmentsByNurseId(
        @PathVariable UUID staffId,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        List<AppointmentResponseDTO> appointments = loadAppointmentsForStaff(staffId, lang, authentication);
        return ResponseEntity.ok(refineForNurseView(appointments));
    }
    @GetMapping("/doctor/{staffId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'ADMIN', 'HOSPITAL_ADMIN')")
    @Operation(summary = "List Appointments by Doctor", description = "Retrieve all appointments scheduled by a doctor.")
    public ResponseEntity<List<AppointmentResponseDTO>> getAppointmentsByDoctorIdForDoctorRole(
        @PathVariable UUID staffId,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);

        return ResponseEntity.ok(
            appointmentService.getAppointmentsByDoctorId(staffId, locale, getUsername(authentication))
        );
    }

    private List<AppointmentResponseDTO> loadAppointmentsForStaff(UUID staffId, String lang, Authentication authentication) {
        Locale locale = parseLocale(lang);
        return appointmentService.getAppointmentsByStaffId(staffId, locale, getUsername(authentication));
    }

    private List<AppointmentResponseDTO> refineForNurseView(List<AppointmentResponseDTO> appointments) {
        if (appointments == null || appointments.isEmpty()) {
            return List.of();
        }
        return appointments.stream()
            .filter(Objects::nonNull)
            .toList();
    }

    private Locale parseLocale(String header) {
        if (header == null || header.isBlank()) {
            return Locale.getDefault();
        }
        String first = header.split(",")[0].trim().replace('_','-');
        if (!isValidLocaleTag(first)) {
            return Locale.getDefault();
        }
        try {
            Locale.Builder b = new Locale.Builder();
            String[] parts = first.split("-");
            b.setLanguage(parts[0]);
            if (parts.length >= 2) {
                b.setRegion(parts[1]);
            }
            if (parts.length >= 3) {
                b.setVariant(parts[2]);
            }
            return b.build();
        } catch (Exception e) {
            return Locale.getDefault();
        }
    }

    private boolean isValidLocaleTag(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String[] segments = candidate.split("-");
        if (segments.length == 0) {
            return false;
        }

        if (!isAlphaSegment(segments[0])) {
            return false;
        }

        for (int i = 1; i < segments.length; i++) {
            if (!isAlphanumericSegment(segments[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean isAlphaSegment(String segment) {
        if (segment == null || segment.isBlank() || segment.length() < 2 || segment.length() > 8) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (!Character.isLetter(ch)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAlphanumericSegment(String segment) {
        if (segment == null || segment.isBlank() || segment.length() < 2 || segment.length() > 8) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "appointmentDate");
        }

        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (property.isEmpty()) {
            property = "appointmentDate";
        }

        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1) {
            String dir = parts[1].trim();
            if (dir.equalsIgnoreCase("asc")) {
                direction = Sort.Direction.ASC;
            }
        }

        return Sort.by(direction, property);
    }

}
