package com.example.hms.controller;

import com.example.hms.payload.dto.OrganizationSecurityPolicyRequestDTO;
import com.example.hms.payload.dto.OrganizationSecurityPolicyResponseDTO;
import com.example.hms.service.OrganizationSecurityPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/security-policies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN')")
public class OrganizationSecurityPolicyController {
    private final OrganizationSecurityPolicyService policyService;

    @GetMapping
    public ResponseEntity<List<OrganizationSecurityPolicyResponseDTO>> getAllPolicies() {
        return ResponseEntity.ok(policyService.getAllPoliciesAsDto());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationSecurityPolicyResponseDTO> getPolicyById(@PathVariable UUID id) {
        OrganizationSecurityPolicyResponseDTO dto = policyService.getPolicyByIdAsDto(id);
        return ResponseEntity.ok(dto);
    }

    @PostMapping
    public ResponseEntity<OrganizationSecurityPolicyResponseDTO> createPolicy(
            @Valid @RequestBody OrganizationSecurityPolicyRequestDTO request) {
        return ResponseEntity.ok(policyService.createPolicyFromDto(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrganizationSecurityPolicyResponseDTO> updatePolicy(
            @PathVariable UUID id,
            @Valid @RequestBody OrganizationSecurityPolicyRequestDTO request) {
        return ResponseEntity.ok(policyService.updatePolicyFromDto(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID id) {
        policyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }
}

