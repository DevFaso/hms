package com.example.hms.controller;

import com.example.hms.model.User;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/super-admin/export")
@RequiredArgsConstructor
@Tag(name = "Super Admin: Data Export", description = "Bulk data export for platform reporting")
public class SuperAdminDataExportController {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Export all users as CSV", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> exportUsers() {
        List<User> users = userRepository.findAll();

        StringBuilder csv = new StringBuilder();
        csv.append("ID,Username,Email,First Name,Last Name,Phone,Active,Deleted,Last Login\n");
        for (User u : users) {
            csv.append(escapeCsv(str(u.getId()))).append(',');
            csv.append(escapeCsv(u.getUsername())).append(',');
            csv.append(escapeCsv(u.getEmail())).append(',');
            csv.append(escapeCsv(u.getFirstName())).append(',');
            csv.append(escapeCsv(u.getLastName())).append(',');
            csv.append(escapeCsv(u.getPhoneNumber())).append(',');
            csv.append(u.isActive()).append(',');
            csv.append(u.isDeleted()).append(',');
            csv.append(escapeCsv(u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : "")).append('\n');
        }

        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users-export.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .contentLength(content.length)
            .body(content);
    }

    @GetMapping("/patients")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    @Operation(summary = "Export all patients as CSV", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> exportPatients() {
        var patients = patientRepository.findAll();

        StringBuilder csv = new StringBuilder();
        csv.append("ID,First Name,Last Name,Date of Birth,Gender,Email,Phone,Blood Type,City,Country\n");
        for (var p : patients) {
            csv.append(escapeCsv(str(p.getId()))).append(',');
            csv.append(escapeCsv(p.getFirstName())).append(',');
            csv.append(escapeCsv(p.getLastName())).append(',');
            csv.append(escapeCsv(p.getDateOfBirth() != null ? p.getDateOfBirth().toString() : "")).append(',');
            csv.append(escapeCsv(p.getGender())).append(',');
            csv.append(escapeCsv(p.getEmail())).append(',');
            csv.append(escapeCsv(p.getPhoneNumberPrimary())).append(',');
            csv.append(escapeCsv(p.getBloodType())).append(',');
            csv.append(escapeCsv(p.getCity())).append(',');
            csv.append(escapeCsv(p.getCountry())).append('\n');
        }

        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"patients-export.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .contentLength(content.length)
            .body(content);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        String safe = value;
        if (!safe.isEmpty()) {
            char first = safe.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                safe = "'" + safe;
            }
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
