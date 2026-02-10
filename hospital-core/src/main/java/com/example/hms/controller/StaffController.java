package com.example.hms.controller;

import com.example.hms.payload.dto.StaffResponseDTO;
import com.example.hms.payload.dto.StaffRequestDTO;
import com.example.hms.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.Email;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/staff")
@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
@Tag(name = "Staff Management", description = "Endpoints for managing hospital staff members.")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @Operation(summary = "List all staff")
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<List<StaffResponseDTO>> getAllStaff(
        @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.getAllStaff(locale));
    }

    @Operation(summary = "Create staff profile")
    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<StaffResponseDTO> create(
        @RequestBody StaffRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.createStaff(request, locale));
    }

    @Operation(summary = "Update staff profile")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<StaffResponseDTO> update(
        @PathVariable UUID id,
        @RequestBody StaffRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.updateStaff(id, request, locale));
    }

    @Operation(summary = "Soft delete staff profile")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) String lang
    ) {
        Locale locale = parseLocale(lang);
        staffService.deleteStaff(id, locale);
        return ResponseEntity.noContent().build();
    }

    // 2.1 Search: by user email
    @Operation(summary = "Find staff by user email")
    @GetMapping("/search/by-email")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<List<StaffResponseDTO>> getByUserEmail(
        @RequestParam @Email String email,
        @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.getStaffByUserEmail(email, locale));
    }

    // 2.2 Search: by user phone
    @Operation(summary = "Find staff by user phone number")
    @GetMapping("/search/by-phone")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<List<StaffResponseDTO>> getByUserPhone(
        @RequestParam String phone,
        @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.getStaffByUserPhoneNumber(phone, locale));
    }

    // 2.3 Get any license for a given user (first/any)
    @Operation(summary = "Get any license number by userId")
    @GetMapping("/users/{userId}/license")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Optional<String>> getAnyLicense(
        @PathVariable UUID userId) {
        return ResponseEntity.ok(staffService.getAnyLicenseByUserId(userId));
    }

    // 2.4 Get staff by id but only if active
    @Operation(summary = "Get staff by id (active only)")
    @GetMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Optional<StaffResponseDTO>> getActiveById(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.getStaffByIdAndActiveTrue(id, locale));
    }

    // 2.5 Existence checks
    @Operation(summary = "Check existence by staffId + hospitalId + active=true")
    @GetMapping("/{id}/exists-in-hospital")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Boolean> existsInHospitalActive(
        @PathVariable UUID id,
        @RequestParam UUID hospitalId) {
        return ResponseEntity.ok(staffService.existsByIdAndHospitalIdAndActiveTrue(id, hospitalId));
    }

    @Operation(summary = "Check if license number exists for a given user")
    @GetMapping("/license/exists")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Boolean> existsLicenseForUser(
        @RequestParam String licenseNumber,
        @RequestParam UUID userId) {
        return ResponseEntity.ok(staffService.existsByLicenseNumberAndUserId(licenseNumber, userId));
    }

    // 2.6 Hospital scoped queries (paged)
    @Operation(summary = "Paged staff by hospital")
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Page<StaffResponseDTO>> pageByHospital(
        @PathVariable UUID hospitalId,
        @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(staffService.getStaffByHospitalId(hospitalId, pageable));
    }

    @Operation(summary = "Paged staff by hospital (active only)")
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Page<StaffResponseDTO>> pageByHospitalActive(
        @PathVariable UUID hospitalId,
        @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(staffService.getStaffByHospitalIdAndActiveTrue(hospitalId, pageable));
    }

    // 2.7 First staff record for user (oldest)
    @Operation(summary = "First staff by user (oldest by creation date)")
    @GetMapping("/users/{userId}/first")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<Optional<StaffResponseDTO>> getFirstForUser(
        @PathVariable UUID userId,
        @RequestHeader(name = "Accept-Language", required = false) String lang) {
        Locale locale = parseLocale(lang);
        return ResponseEntity.ok(staffService.getFirstStaffByUserIdOrderByCreatedAtAsc(userId, locale));
    }

    private Locale parseLocale(String header) {
        if (header == null || header.isBlank()) return Locale.getDefault();
        // Example header: en-US,en;q=0.9 -> take first token before comma
        String first = header.split(",")[0].trim();
        // Replace underscore with dash and validate basic pattern
        first = first.replace('_','-');
        if (!first.matches("^[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})*$")) {
            return Locale.getDefault();
        }
        try {
            Locale.Builder builder = new Locale.Builder();
            builder.setLanguage(first.split("-")[0]);
            String[] parts = first.split("-");
            if (parts.length >= 2) builder.setRegion(parts[1]);
            if (parts.length >= 3) builder.setVariant(parts[2]);
            return builder.build();
        } catch (Exception e) {
            return Locale.getDefault();
        }
    }
}
