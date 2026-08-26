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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import static com.example.hms.config.SecurityConstants.CONSULTING_CLINICIANS_ROLES;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/appointments")
@Tag(name = "Appointment Management", description = "Endpoints for creating and managing patient appointments with role-based treatment access.")
@RequiredArgsConstructor
public class AppointmentController {

    /**
     * Appointment READ. A consulting clinician needs the visit calendar to
     * place their own work against it (pre-operative clinic, therapy
     * series). Admitted by role audit D7 — booking and cancelling stay
     * with reception and the treating team.
     */
    /**
     * Booking. Identical to the read list BEFORE role audit D7, and kept
     * separate precisely so widening the read list does not silently hand
     * consulting clinicians the ability to book — they read the calendar,
     * they do not schedule against it.
     */
    private static final String APPOINTMENT_BOOK_ROLES =
        "hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'PATIENT')";

    /**
     * Everyone who may CHANGE an appointment: move it, change its status,
     * check the patient in, cancel it.
     *
     * <p>A constant because this list was pasted inline at five call sites
     * and one copy silently lost RECEPTIONIST — so a receptionist could book
     * an appointment and cancel it but not move it, which is the commonest
     * task at a front desk. It returned a bare 403 from PUT /appointments/{id}
     * while the portal route guard happily let them reach the button.
     * Reported from dev 2026-08-26.
     */
    private static final String APPOINTMENT_MANAGE_ROLES =
        "hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE')";

    private static final String APPOINTMENT_READ_ROLES = "hasAnyRole("
        + "'SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'RECEPTIONIST', 'DOCTOR', 'NURSE', 'MIDWIFE', 'PATIENT',"
        + CONSULTING_CLINICIANS_ROLES + ")";

    private static final String SORT_APPOINTMENT_DATE = "appointmentDate";

    // ---- LIST BY PATIENT USERNAME ----
    @GetMapping("/patients/username/{patientUsername}")
    @PreAuthorize(APPOINTMENT_READ_ROLES)
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
    private final com.example.hms.service.AppointmentReminderService appointmentReminderService;
    private final MessageSource messageSource;

    /**
     * Manual twin of the AppointmentReminderScheduler sweep (P1 #7) — same
     * pattern as the referral-expiry and critical-escalation triggers.
     * Returns the number of patients reminded.
     */
    @PostMapping("/reminders/run")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Run the appointment reminder sweep now",
        description = "Mirrors the scheduled sweep; reminds patients of appointments starting within the lead window.")
    public ResponseEntity<java.util.Map<String, Integer>> runReminderSweep() {
        int reminded = appointmentReminderService.sendDueReminders();
        return ResponseEntity.ok(java.util.Map.of("reminded", reminded));
    }

    // Helper to get username (or a custom UserPrincipal with more info)
    private String getUsername(Authentication auth) {
        return auth.getName();
    }

    // ---- CREATE ----
    @PostMapping
    @PreAuthorize(APPOINTMENT_BOOK_ROLES)
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
    @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
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
    @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
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
    @PreAuthorize(APPOINTMENT_READ_ROLES)
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
    @PreAuthorize(APPOINTMENT_READ_ROLES)
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

    // ---- LIST ALL (with optional query-param filtering) ----
    @GetMapping
    @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
    public ResponseEntity<List<AppointmentResponseDTO>> getAllAppointments(
        @RequestParam(required = false) UUID patientId,
        @RequestParam(required = false) UUID staffId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String search,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "50") int size,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);
        // Normalize blank search to null so it does not bypass the no-filters branch
        String normalizedSearch = (search != null && search.isBlank()) ? null : search;
        // When no filters provided, fall back to the original user-scoped list
        if (patientId == null && staffId == null && hospitalId == null
                && fromDate == null && toDate == null && normalizedSearch == null) {
            return ResponseEntity.ok(
                appointmentService.getAppointmentsForUser(getUsername(authentication), locale)
            );
        }
        AppointmentFilterDTO filter = AppointmentFilterDTO.builder()
            .patientId(patientId)
            .staffId(staffId)
            .hospitalId(hospitalId)
            .fromDate(fromDate)
            .toDate(toDate)
            .search(normalizedSearch)
            .build();
        // Sonar S6890 (Pattern 10): Math.clamp replaces the
        // Math.min(Math.max(size, 1), 200) idiom for clarity.
        // Math.max(page, 0) is still needed because page has no upper
        // bound — only a floor.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, SORT_APPOINTMENT_DATE));
        Page<AppointmentResponseDTO> resultPage = appointmentService.searchAppointments(
            filter, pageable, locale, getUsername(authentication));
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(resultPage.getTotalElements()));
        headers.add("X-Total-Pages", String.valueOf(resultPage.getTotalPages()));
        headers.add("X-Has-Next", String.valueOf(resultPage.hasNext()));
        return ResponseEntity.ok().headers(headers).body(resultPage.getContent());
    }

    // ---- DELETE ----
    @DeleteMapping("/{id}")
    // Deliberately NARROWER than APPOINTMENT_MANAGE_ROLES: a receptionist
    // cancels an appointment (which keeps the record and its history) rather
    // than deleting it. Not the same omission as the PUT above — this one is
    // on purpose.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'STAFF', 'DOCTOR', 'NURSE', 'MIDWIFE')")
    public ResponseEntity<String> deleteAppointment(
        @PathVariable UUID id,
    @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
    Locale locale = parseLocale(lang);
        appointmentService.deleteAppointment(id, locale, getUsername(authentication));
        String rawMessage = messageSource.getMessage("appointment.deleted", new Object[]{id}, locale);
        String safeMessage = HtmlUtils.htmlEscape(rawMessage);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(safeMessage);
    }

    // ---- LIST BY PATIENT ----
    @GetMapping("/patients/{patientId}")
    @PreAuthorize(APPOINTMENT_READ_ROLES)
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
    @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
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
        } catch (RuntimeException e) {
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
            return Sort.by(Sort.Direction.DESC, SORT_APPOINTMENT_DATE);
        }

        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (property.isEmpty()) {
            property = SORT_APPOINTMENT_DATE;
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


    // ---- CALENDAR (Cadence visual scheduling grid) ----

    /**
     * Date-range slice for the Cadence calendar grid. Hospital-scoped;
     * optionally filtered by provider. Caps the viewport at 31 days to
     * keep the result set bounded; broader ranges return 400.
     */
    @GetMapping("/calendar")
    @PreAuthorize(APPOINTMENT_MANAGE_ROLES)
    @Operation(summary = "List appointments in a date range for calendar rendering")
    public ResponseEntity<List<com.example.hms.payload.dto.appointment.AppointmentCalendarEventDTO>> getCalendarEvents(
        @RequestParam UUID hospitalId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) UUID staffId,
        Authentication authentication,
        @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        if (from == null || to == null || from.isAfter(to)) {
            return ResponseEntity.badRequest().build();
        }
        // 31-day cap is INCLUSIVE: ChronoUnit.DAYS.between is exclusive of
        // the end date, so a 31-day range yields 30; reject when > 30.
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 30) {
            return ResponseEntity.badRequest().build();
        }
        Locale locale = (acceptLanguage == null || acceptLanguage.isBlank())
            ? Locale.ENGLISH : Locale.forLanguageTag(acceptLanguage);
        return ResponseEntity.ok(appointmentService.getCalendarEvents(
            hospitalId, from, to, staffId, getUsername(authentication), locale));
    }
}
