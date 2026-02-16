package com.example.hms.controller;

import com.example.hms.payload.dto.medicalhistory.FamilyHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO;
import com.example.hms.service.FamilyHistoryService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.SocialHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/medical-history")
@RequiredArgsConstructor
@Tag(name = "Medical History", description = "Endpoints for managing patient medical history including social, family, and immunization records")
public class MedicalHistoryController {

    private final SocialHistoryService socialHistoryService;
    private final FamilyHistoryService familyHistoryService;
    private final ImmunizationService immunizationService;

    // ========== Social History Endpoints ==========

    @PostMapping("/social")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Create social history record", description = "Create a new social history record for a patient")
    public ResponseEntity<SocialHistoryResponseDTO> createSocialHistory(
            @Valid @RequestBody SocialHistoryRequestDTO requestDTO) {
        SocialHistoryResponseDTO response = socialHistoryService.createSocialHistory(requestDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/social/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get social history by ID", description = "Retrieve a specific social history record")
    public ResponseEntity<SocialHistoryResponseDTO> getSocialHistoryById(@PathVariable UUID id) {
        SocialHistoryResponseDTO response = socialHistoryService.getSocialHistoryById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/social")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get all social histories for patient", description = "Retrieve all social history records for a specific patient")
    public ResponseEntity<List<SocialHistoryResponseDTO>> getSocialHistoriesByPatient(
            @PathVariable UUID patientId) {
        List<SocialHistoryResponseDTO> histories = socialHistoryService.getSocialHistoriesByPatientId(patientId);
        return ResponseEntity.ok(histories);
    }

    @GetMapping("/patient/{patientId}/social/current")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get current active social history", description = "Retrieve the current active social history for a patient")
    public ResponseEntity<SocialHistoryResponseDTO> getCurrentSocialHistory(@PathVariable UUID patientId) {
        SocialHistoryResponseDTO response = socialHistoryService.getCurrentSocialHistory(patientId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/social/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Update social history", description = "Update an existing social history record")
    public ResponseEntity<SocialHistoryResponseDTO> updateSocialHistory(
            @PathVariable UUID id,
            @Valid @RequestBody SocialHistoryRequestDTO requestDTO) {
        SocialHistoryResponseDTO response = socialHistoryService.updateSocialHistory(id, requestDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/social/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete social history", description = "Soft delete a social history record (admin only)")
    public ResponseEntity<Void> deleteSocialHistory(@PathVariable UUID id) {
        socialHistoryService.deleteSocialHistory(id);
        return ResponseEntity.noContent().build();
    }

    // ========== Family History Endpoints ==========

    @PostMapping("/family")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Create family history record", description = "Create a new family history record for a patient")
    public ResponseEntity<FamilyHistoryResponseDTO> createFamilyHistory(
            @Valid @RequestBody FamilyHistoryRequestDTO requestDTO) {
        FamilyHistoryResponseDTO response = familyHistoryService.createFamilyHistory(requestDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/family/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get family history by ID", description = "Retrieve a specific family history record")
    public ResponseEntity<FamilyHistoryResponseDTO> getFamilyHistoryById(@PathVariable UUID id) {
        FamilyHistoryResponseDTO response = familyHistoryService.getFamilyHistoryById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/family")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get all family histories for patient", description = "Retrieve all family history records for a specific patient")
    public ResponseEntity<List<FamilyHistoryResponseDTO>> getFamilyHistoriesByPatient(
            @PathVariable UUID patientId) {
        List<FamilyHistoryResponseDTO> histories = familyHistoryService.getFamilyHistoriesByPatientId(patientId);
        return ResponseEntity.ok(histories);
    }

    @GetMapping("/patient/{patientId}/family/genetic")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Get genetic conditions", description = "Retrieve family history records with genetic conditions for a patient")
    public ResponseEntity<List<FamilyHistoryResponseDTO>> getGeneticConditions(@PathVariable UUID patientId) {
        List<FamilyHistoryResponseDTO> geneticConditions = familyHistoryService.getGeneticConditions(patientId);
        return ResponseEntity.ok(geneticConditions);
    }

    @GetMapping("/patient/{patientId}/family/screening-needed")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Get screening recommendations", description = "Retrieve family history records requiring screening for a patient")
    public ResponseEntity<List<FamilyHistoryResponseDTO>> getScreeningRecommendations(
            @PathVariable UUID patientId) {
        List<FamilyHistoryResponseDTO> screeningNeeded = familyHistoryService.getScreeningRecommendations(patientId);
        return ResponseEntity.ok(screeningNeeded);
    }

    @GetMapping("/patient/{patientId}/family/category/{category}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Get family histories by condition category", description = "Retrieve family history records by condition category")
    public ResponseEntity<List<FamilyHistoryResponseDTO>> getFamilyHistoriesByCategory(
            @PathVariable UUID patientId,
            @PathVariable String category) {
        List<FamilyHistoryResponseDTO> histories = 
                familyHistoryService.getFamilyHistoriesByConditionCategory(patientId, category);
        return ResponseEntity.ok(histories);
    }

    @PutMapping("/family/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE')")
    @Operation(summary = "Update family history", description = "Update an existing family history record")
    public ResponseEntity<FamilyHistoryResponseDTO> updateFamilyHistory(
            @PathVariable UUID id,
            @Valid @RequestBody FamilyHistoryRequestDTO requestDTO) {
        FamilyHistoryResponseDTO response = familyHistoryService.updateFamilyHistory(id, requestDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/family/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete family history", description = "Soft delete a family history record (admin only)")
    public ResponseEntity<Void> deleteFamilyHistory(@PathVariable UUID id) {
        familyHistoryService.deleteFamilyHistory(id);
        return ResponseEntity.noContent().build();
    }

    // ========== Immunization Endpoints ==========

    @PostMapping("/immunizations")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Create immunization record", description = "Create a new immunization record for a patient")
    public ResponseEntity<ImmunizationResponseDTO> createImmunization(
            @Valid @RequestBody ImmunizationRequestDTO requestDTO) {
        ImmunizationResponseDTO response = immunizationService.createImmunization(requestDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/immunizations/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get immunization by ID", description = "Retrieve a specific immunization record")
    public ResponseEntity<ImmunizationResponseDTO> getImmunizationById(@PathVariable UUID id) {
        ImmunizationResponseDTO response = immunizationService.getImmunizationById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patient/{patientId}/immunizations")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_LAB_SCIENTIST', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get all immunizations for patient", description = "Retrieve all immunization records for a specific patient")
    public ResponseEntity<List<ImmunizationResponseDTO>> getImmunizationsByPatient(
            @PathVariable UUID patientId) {
        List<ImmunizationResponseDTO> immunizations = immunizationService.getImmunizationsByPatientId(patientId);
        return ResponseEntity.ok(immunizations);
    }

    @GetMapping("/patient/{patientId}/immunizations/vaccine/{vaccineCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get immunizations by vaccine code", description = "Retrieve all immunizations for a specific vaccine type")
    public ResponseEntity<List<ImmunizationResponseDTO>> getImmunizationsByVaccine(
            @PathVariable UUID patientId,
            @PathVariable String vaccineCode) {
        List<ImmunizationResponseDTO> immunizations = 
                immunizationService.getImmunizationsByVaccineCode(patientId, vaccineCode);
        return ResponseEntity.ok(immunizations);
    }

    @GetMapping("/patient/{patientId}/immunizations/overdue")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get overdue immunizations", description = "Retrieve overdue immunizations for a patient")
    public ResponseEntity<List<ImmunizationResponseDTO>> getOverdueImmunizations(@PathVariable UUID patientId) {
        List<ImmunizationResponseDTO> overdueImmunizations = immunizationService.getOverdueImmunizations(patientId);
        return ResponseEntity.ok(overdueImmunizations);
    }

    @GetMapping("/patient/{patientId}/immunizations/upcoming")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get upcoming immunizations", description = "Retrieve upcoming immunizations within a date range")
    public ResponseEntity<List<ImmunizationResponseDTO>> getUpcomingImmunizations(
            @PathVariable UUID patientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<ImmunizationResponseDTO> upcomingImmunizations = 
                immunizationService.getUpcomingImmunizations(patientId, startDate, endDate);
        return ResponseEntity.ok(upcomingImmunizations);
    }

    @GetMapping("/patient/{patientId}/immunizations/reminders-needed")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Get immunizations needing reminders", description = "Retrieve immunizations that need reminder notifications")
    public ResponseEntity<List<ImmunizationResponseDTO>> getImmunizationsNeedingReminders(
            @PathVariable UUID patientId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reminderDate) {
        List<ImmunizationResponseDTO> needingReminders = 
                immunizationService.getImmunizationsNeedingReminders(patientId, reminderDate);
        return ResponseEntity.ok(needingReminders);
    }

    @PatchMapping("/immunizations/{id}/mark-reminder-sent")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_RECEPTIONIST')")
    @Operation(summary = "Mark reminder as sent", description = "Mark that a reminder has been sent for an immunization")
    public ResponseEntity<Void> markReminderSent(@PathVariable UUID id) {
        immunizationService.markReminderSent(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/immunizations/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_PHARMACIST')")
    @Operation(summary = "Update immunization", description = "Update an existing immunization record")
    public ResponseEntity<ImmunizationResponseDTO> updateImmunization(
            @PathVariable UUID id,
            @Valid @RequestBody ImmunizationRequestDTO requestDTO) {
        ImmunizationResponseDTO response = immunizationService.updateImmunization(id, requestDTO);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/immunizations/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN')")
    @Operation(summary = "Delete immunization", description = "Soft delete an immunization record (admin only)")
    public ResponseEntity<Void> deleteImmunization(@PathVariable UUID id) {
        immunizationService.deleteImmunization(id);
        return ResponseEntity.noContent().build();
    }
}
