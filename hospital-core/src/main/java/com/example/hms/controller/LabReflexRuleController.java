package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.LabReflexRuleRequestDTO;
import com.example.hms.payload.dto.LabReflexRuleResponseDTO;
import com.example.hms.service.LabReflexRuleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/lab-reflex-rules")
@Tag(name = "Lab Reflex Rules", description = "Manage auto-reflex and add-on test rules triggered on result entry")
@RequiredArgsConstructor
public class LabReflexRuleController {

    private final LabReflexRuleService labReflexRuleService;

    @PostMapping
    @PreAuthorize("hasAnyRole('LAB_MANAGER', 'LAB_SCIENTIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create Reflex Rule",
               description = "Creates a new reflex/add-on rule. Condition JSON examples: {\"severityFlag\":\"ABNORMAL\"} or {\"thresholdOperator\":\"GT\",\"thresholdValue\":11.0}")
    @ApiResponse(responseCode = "201", description = "Rule created")
    public ResponseEntity<ApiResponseWrapper<LabReflexRuleResponseDTO>> createRule(
        @RequestBody LabReflexRuleRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabReflexRuleResponseDTO created = labReflexRuleService.createRule(request, locale);
        return ResponseEntity.status(201).body(ApiResponseWrapper.success(created));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'LAB_SCIENTIST', 'LAB_MANAGER', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List All Reflex Rules")
    @ApiResponse(responseCode = "200", description = "Rules retrieved")
    public ResponseEntity<ApiResponseWrapper<List<LabReflexRuleResponseDTO>>> getAllRules(
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        return ResponseEntity.ok(ApiResponseWrapper.success(
            labReflexRuleService.getAllRules(locale)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('LAB_MANAGER', 'LAB_SCIENTIST', 'HOSPITAL_ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Update Reflex Rule", description = "Updates condition, active flag, or description of an existing reflex rule.")
    @ApiResponse(responseCode = "200", description = "Rule updated")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    public ResponseEntity<ApiResponseWrapper<LabReflexRuleResponseDTO>> updateRule(
        @PathVariable UUID id,
        @RequestBody LabReflexRuleRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) Locale locale) {

        LabReflexRuleResponseDTO updated = labReflexRuleService.updateRule(id, request, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(updated));
    }
}
