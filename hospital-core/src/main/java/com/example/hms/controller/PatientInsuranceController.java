package com.example.hms.controller;

import com.example.hms.payload.dto.LinkPatientInsuranceRequestDTO;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.service.PatientInsuranceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/patient-insurances")
@Tag(name = "Patient Insurance Management", description = "APIs for managing patient insurance records")
@RequiredArgsConstructor
public class PatientInsuranceController {

    private final PatientInsuranceService patientInsuranceService;
    private final MessageSource messageSource;

    // === Upsert + link by NATURAL KEY (preferred; replaces POST) ===
    @PutMapping("/link")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR','PATIENT')")
    @Operation(
        summary = "Create or update insurance and link to patient (and optionally hospital)",
        description = """
            Idempotent upsert using natural keys (patientId + payerCode + policyNumber).
            If the insurance does not exist, it is created and linked; otherwise it is updated and (re)linked.
            Patients can only upsert/link their own insurance and cannot set a hospitalId.
            Staff/Admin can set X-Hospital-Id or request.hospitalId, and optionally set 'primary'.
        """
    )
    @Parameter(name = "X-Act-As", in = ParameterIn.HEADER,
        description = "Acting mode: PATIENT | STAFF (default STAFF if omitted)")
    @Parameter(name = "X-Hospital-Id", in = ParameterIn.HEADER,
        description = "Required when acting as STAFF; UUID of the hospital")
    @Parameter(name = "X-Role-Code", in = ParameterIn.HEADER,
        description = "Optional STAFF role pin: RECEPTIONIST | NURSE | DOCTOR")
    public ResponseEntity<PatientInsuranceResponseDTO> upsertAndLink(
        @Valid @RequestBody LinkPatientInsuranceRequestDTO request,
        Locale locale,
        com.example.hms.security.ActingContext ctx
    ) {
        // REQUIRE: request must include patientId, payerCode, policyNumber
        PatientInsuranceResponseDTO linked = patientInsuranceService.upsertAndLinkByNaturalKey(request, ctx, locale);
        return ResponseEntity.ok(linked);
    }

    // === Existing reads remain unchanged ===

    @GetMapping("/{insuranceId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR','PATIENT')")
    public ResponseEntity<PatientInsuranceResponseDTO> getPatientInsuranceById(
        @PathVariable UUID insuranceId,
        Locale locale
    ) {
        return ResponseEntity.ok(patientInsuranceService.getPatientInsuranceById(insuranceId, locale));
    }

    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR','PATIENT')")
    public ResponseEntity<List<PatientInsuranceResponseDTO>> getInsurancesByPatientId(
        @PathVariable UUID patientId,
        Locale locale
    ) {
        return ResponseEntity.ok(patientInsuranceService.getInsurancesByPatientId(patientId, locale));
    }

    // Optional: keep this for callers that know the insurance UUID
    @PutMapping("/{insuranceId}/link")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR','PATIENT')")
    public ResponseEntity<PatientInsuranceResponseDTO> linkById(
        @PathVariable UUID insuranceId,
        @Valid @RequestBody LinkPatientInsuranceRequestDTO request,
        Locale locale,
        com.example.hms.security.ActingContext ctx
    ) {
        return ResponseEntity.ok(patientInsuranceService.linkPatientInsurance(insuranceId, request, ctx, locale));
    }

    @PutMapping("/{insuranceId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR')")
    public ResponseEntity<PatientInsuranceResponseDTO> updatePatientInsurance(
        @PathVariable UUID insuranceId,
        @Valid @RequestBody com.example.hms.payload.dto.PatientInsuranceRequestDTO request,
        Locale locale
    ) {
        return ResponseEntity.ok(patientInsuranceService.updatePatientInsurance(insuranceId, request, locale));
    }

    @DeleteMapping("/{insuranceId}")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','RECEPTIONIST','NURSE','DOCTOR')")
    public ResponseEntity<String> deletePatientInsurance(
        @PathVariable UUID insuranceId,
        Locale locale
    ) {
        patientInsuranceService.deletePatientInsurance(insuranceId, locale);
        String msg = messageSource.getMessage("patientinsurance.deleted", new Object[]{insuranceId}, locale);
        return ResponseEntity.ok(msg);
    }
}
