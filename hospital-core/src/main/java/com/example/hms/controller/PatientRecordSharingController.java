package com.example.hms.controller;

import com.example.hms.payload.dto.PatientRecordDTO;
import com.example.hms.payload.dto.RecordShareRequestDTO;
import com.example.hms.service.PatientRecordSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/records")
@RequiredArgsConstructor
@Tag(name = "Patient Record Sharing", description = "Endpoints for sharing and exporting patient records with consent verification.")
public class PatientRecordSharingController {

    private final PatientRecordSharingService sharingService;
    @Operation(summary = "Share Patient Records",
        description = "Returns the structured patient record data if sharing consent is active.")
    @ApiResponse(responseCode = "200", description = "Patient record shared successfully.")
    @ApiResponse(responseCode = "400", description = "Missing or invalid request parameters.")
    @ApiResponse(responseCode = "403", description = "Consent not granted or expired.")
    @ApiResponse(responseCode = "404", description = "Patient or hospital not found.")
    @PostMapping(value = "/share", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PatientRecordDTO> shareRecords(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Payload containing patientId, source hospital, and target hospital",
            required = true
        )
        @RequestBody RecordShareRequestDTO shareRequest
    ) {
        PatientRecordDTO sharedRecord = sharingService.getPatientRecord(
            shareRequest.getPatientId(),
            shareRequest.getFromHospitalId(),
            shareRequest.getToHospitalId()
        );
        return ResponseEntity.ok(sharedRecord);
    }


    @GetMapping("/export")
    @PreAuthorize("hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<byte[]> exportRecords(
            @RequestParam UUID patientId,
            @RequestParam UUID fromHospitalId,
            @RequestParam UUID toHospitalId,
            @RequestParam String format) {

        byte[] fileContent = sharingService.exportPatientRecord(patientId, fromHospitalId, toHospitalId, format);

        String contentType = switch (format.toLowerCase()) {
            case "csv" -> "text/csv";
            case "pdf" -> "application/pdf";
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };

        String fileName = "patient_record." + format.toLowerCase();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(fileContent);
    }

}
