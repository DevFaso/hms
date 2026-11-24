package com.example.hms.controller;

import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.PatientNationalUniqueIdService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/patients/national-unique-id")
@RequiredArgsConstructor
@WriteAudited(skip = true, reason = "Read-only identity lookup; full disclosure is audited transactionally by the service")
public class PatientNationalUniqueIdController {
    private static final String EDITORS = "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE',"
        + "'ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";
    private static final String SEARCHERS = "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR',"
        + "'ROLE_NURSE','ROLE_MIDWIFE','ROLE_LAB_SCIENTIST','ROLE_PHARMACIST','ROLE_RADIOLOGIST',"
        + "'ROLE_PHYSIOTHERAPIST','ROLE_SUPER_ADMIN','ROLE_BILLING_SPECIALIST','ROLE_ACCOUNTANT')";
    private final PatientNationalUniqueIdService service;

    /** No generated toString: request bodies must never print identity data. */
    @Getter @Setter
    @com.example.hms.validation.RejectsUnknownFields(com.example.hms.validation.RejectsUnknownFields.Kind.SEARCH)
    public static class Lookup {
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
        private String nationalUniqueId;
        private UUID excludePatientId;
    }

    @Getter @RequiredArgsConstructor
    public static class FullValue {
        private final String nationalUniqueId;
    }

    @PostMapping("/check") @PreAuthorize(EDITORS)
    @com.example.hms.security.ReadsOnly("Exact identity duplicate check without modifying patient data")
    public PatientNationalUniqueIdService.DuplicateWarning check(@RequestBody Lookup request) {
        return service.check(request.getNationalUniqueId(), request.getExcludePatientId());
    }

    @PostMapping("/search") @PreAuthorize(SEARCHERS)
    @com.example.hms.security.ReadsOnly("Exact scoped patient search without modifying patient data")
    public List<PatientResponseDTO> search(@RequestBody Lookup request) {
        return service.search(request.getNationalUniqueId());
    }

    @PostMapping("/{patientId}/reveal") @PreAuthorize(EDITORS)
    @com.example.hms.security.ReadsOnly("Identity disclosure with a required access audit only")
    public FullValue reveal(@PathVariable UUID patientId) {
        return new FullValue(service.reveal(patientId));
    }
}
