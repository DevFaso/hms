package com.example.hms.controller;

import com.example.hms.payload.dto.HospitalOrganizationAssignmentRequest;
import com.example.hms.payload.dto.HospitalRequestDTO;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.HospitalWithDepartmentsDTO;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.service.HospitalService;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.*;

@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600 )
@RestController
@RequestMapping("/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;
    private final MessageSource messageSource;

    
    public HospitalController(HospitalService hospitalService, MessageSource messageSource) {
        this.hospitalService = hospitalService;
        this.messageSource = messageSource;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "') or hasAuthority('" + ROLE_HOSPITAL_ADMIN + "') or hasAuthority('" + ROLE_RECEPTIONIST + "') or hasAuthority('" + ROLE_NURSE + "') or hasAuthority('" + ROLE_MIDWIFE + "')")
    public ResponseEntity<List<HospitalResponseDTO>> getAllHospitals(
            @RequestParam(name = "organizationId", required = false) UUID organizationId,
            @RequestParam(name = "unassignedOnly", required = false) Boolean unassignedOnly,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "state", required = false) String state,
            Locale locale) {
        return ResponseEntity.ok(
                hospitalService.getAllHospitals(organizationId, unassignedOnly, city, state, locale));
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "') or hasAuthority('" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<List<HospitalResponseDTO>> getHospitalsByOrganization(@PathVariable UUID organizationId,
                                                                               Locale locale) {
        return ResponseEntity.ok(hospitalService.getHospitalsByOrganization(organizationId, locale));
    }

    @GetMapping("/with-departments")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "') or hasAuthority('" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<List<HospitalWithDepartmentsDTO>> getHospitalsWithDepartments(
            @RequestParam(name = "hospitalQuery", required = false) String hospitalQuery,
            @RequestParam(name = "departmentQuery", required = false) String departmentQuery,
            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly,
            Locale locale) {
        return ResponseEntity.ok(
                hospitalService.getHospitalsWithDepartments(hospitalQuery, departmentQuery, activeOnly, locale)
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalResponseDTO> createHospital(@Valid @RequestBody HospitalRequestDTO requestDTO, Locale locale) {
        HospitalResponseDTO createdHospital = hospitalService.createHospital(requestDTO, locale);
        return new ResponseEntity<>(createdHospital, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "') or hasAuthority('" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<HospitalResponseDTO> getHospitalById(@PathVariable UUID id, Locale locale) {
        return ResponseEntity.ok(hospitalService.getHospitalById(id, locale));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalResponseDTO> updateHospital(@PathVariable UUID id,
                                                              @Valid @RequestBody HospitalRequestDTO requestDTO,
                                                              Locale locale) {
        return ResponseEntity.ok(hospitalService.updateHospital(id, requestDTO, locale));
    }

    @PutMapping("/{id}/organization")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalResponseDTO> assignHospitalToOrganization(@PathVariable UUID id,
                                                                            @Valid @RequestBody HospitalOrganizationAssignmentRequest request,
                                                                            Locale locale) {
        return ResponseEntity.ok(hospitalService.assignHospitalToOrganization(id, request.getOrganizationId(), locale));
    }

    @DeleteMapping("/{id}/organization")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalResponseDTO> removeHospitalOrganization(@PathVariable UUID id, Locale locale) {
        return ResponseEntity.ok(hospitalService.unassignHospitalFromOrganization(id, locale));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<MessageResponse> deleteHospital(@PathVariable UUID id, Locale locale) {
        hospitalService.deleteHospital(id, locale);
        String message = messageSource.getMessage("hospital.deleted", null, "Hospital deleted successfully.", locale);
        return ResponseEntity.ok(new MessageResponse(message));
    }
}
